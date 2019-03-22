/*
 * RESTHeart - the Web API for MongoDB
 * Copyright (C) SoftInstigate Srl
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.restheart.db.sessions;

import com.mongodb.ClientSessionOptions;
import com.mongodb.MongoClient;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoCredential;
import com.mongodb.MongoQueryException;
import com.mongodb.client.internal.MongoClientDelegate;
import com.mongodb.client.model.Filters;
import static com.mongodb.client.model.Filters.eq;
import com.mongodb.connection.Cluster;
import com.mongodb.internal.session.ServerSessionPool;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import static java.util.Collections.singletonList;
import java.util.List;
import java.util.UUID;
import org.bson.conversions.Bson;
import org.restheart.db.MongoDBClientSingleton;
import static org.restheart.db.sessions.ClientSessionFactory.createClientSession;
import org.restheart.db.sessions.Txn.TransactionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Andrea Di Cesare <andrea@softinstigate.com>
 */
public class SessionsUtils {
    private static final Logger LOGGER = LoggerFactory
            .getLogger(SessionsUtils.class);

    private static MongoClient MCLIENT = MongoDBClientSingleton
            .getInstance().getClient();

    private static MongoClientDelegate delegate;

    static {
        List<MongoCredential> credentialsList
                = MCLIENT.getCredential() != null
                ? singletonList(MCLIENT.getCredential())
                : Collections.<MongoCredential>emptyList();

        delegate = new MongoClientDelegate(
                getCluster(),
                credentialsList,
                MCLIENT);
    }

    @SuppressWarnings("deprecation")
    public static Cluster getCluster() {
        try {
            Class clazz = Class.forName("com.mongodb.Mongo");
            Method getCluster = clazz.getDeclaredMethod("getCluster");
            getCluster.setAccessible(true);

            return (Cluster) getCluster.invoke(MCLIENT);
        } catch (ClassNotFoundException
                | NoSuchMethodException
                | SecurityException
                | IllegalAccessException
                | InvocationTargetException ex) {
            LOGGER.error("error invokng MongoClient.getCluster() through reflection", ex);
            return null;
        }
    }

    public static ServerSessionPool getServerSessionPool() {
        try {
            Class clazz = Class.forName("com.mongodb.Mongo");
            Method getServerSessionPool = clazz.getDeclaredMethod("getServerSessionPool");
            getServerSessionPool.setAccessible(true);

            return (ServerSessionPool) getServerSessionPool.invoke(MCLIENT);
        } catch (ClassNotFoundException
                | NoSuchMethodException
                | SecurityException
                | IllegalAccessException
                | InvocationTargetException ex) {
            LOGGER.error("error invokng MongoClient.getCluster() through reflection", ex);
            return null;
        }
    }

    public static Txn getTxnServerStatus(UUID sid) {
        var options = Sid.getSessionOptions(sid);

        if (!options.isTransacted()) {
            return Txn.newNotSupportingTxn();
        }

        var cso = ClientSessionOptions
                .builder()
                .causallyConsistent(options.isCausallyConsistent())
                .build();

        var cs = createClientSession(
                sid,
                cso,
                MCLIENT.getReadConcern(),
                MCLIENT.getWriteConcern(),
                MCLIENT.getReadPreference(),
                Txn.newNotSupportingTxn());

        // set txnId on ServerSession
        if (cs.getServerSession().getTransactionNumber()
                < 1) {
            ((ServerSessionImpl) cs.getServerSession())
                    .advanceTransactionNumber(1);
        }

        try {
            if (!cs.hasActiveTransaction()) {
                cs.setMessageSentInCurrentTransaction(true);
                cs.startTransaction();
            }
            runDummyReadCommand(cs);
            return new Txn(1, TransactionState.IN);
        } catch (MongoQueryException mqe) {
            var num = getTxnNumFromExc(mqe);

            if (num > 1) {
                cs.advanceServerSessionTransactionNumber(num);
                try {
                    runDummyReadCommand(cs);
                    return new Txn(num, TransactionState.IN);
                } catch (MongoQueryException mqe2) {
                    return new Txn(num, getTxnStateFromExc(mqe2));
                }
            } else {
                return new Txn(num, getTxnStateFromExc(mqe));
            }
        }
    }

    /**
     *
     * @param cs
     * @throws MongoQueryException
     */
    public static void runDummyReadCommand(ClientSessionImpl cs)
            throws MongoQueryException {
        MCLIENT
                .getDatabase("foo")
                .getCollection("bar")
                .find(cs)
                .limit(1)
                .projection(eq("_id", 1))
                .first();
    }

    private static final Bson IMPOSSIBLE_CONDITION = Filters.exists("_id", false);

    private static void runDummyWriteCommand(ClientSessionImpl cs)
            throws MongoCommandException {
        boolean msict = cs.isMessageSentInCurrentTransaction();

        cs.setMessageSentInCurrentTransaction(true);

        if (!cs.hasActiveTransaction()) {
            cs.startTransaction();
        }

        MCLIENT
                .getDatabase("foo")
                .getCollection("bar")
                .updateOne(cs,
                        IMPOSSIBLE_CONDITION,
                        eq("$set", eq("a", 1)));

        cs.setMessageSentInCurrentTransaction(msict);
    }

    private static final String TXT_NUM_ERROR_MSG_PREFIX_STARTED = "because a newer transaction ";
    private static final String TXT_NUM_ERROR_MSG_SUFFIX_STARTED = " has already started";

    private static final String TXT_NUM_ERROR_MSG_PREFIX_ABORTED = "Transaction ";
    private static final String TXT_NUM_ERROR_MSG_SUFFIX_ABORTED = " has been aborted";
    
    private static final String TXT_NUM_ERROR_MSG_PREFIX_COMMITTED = "Transaction ";
    private static final String TXT_NUM_ERROR_MSG_SUFFIX_COMMITTED = " has been committed";
    
    private static final String TXT_NUM_ERROR_MSG_PREFIX_NONE = "Given transaction number ";
    private static final String TXT_NUM_ERROR_MSG_SUFFIX_NONE = " does not match any in-progress transactions";

    /**
     * gets the actual txn id from error messages
     * @param mqe
     * @return 
     */
    private static long getTxnNumFromExc(MongoQueryException mqe) {
        if (mqe.getErrorCode() == 225 && mqe.getErrorMessage() != null) {
            int start = mqe.getErrorMessage().indexOf(TXT_NUM_ERROR_MSG_PREFIX_STARTED)
                    + TXT_NUM_ERROR_MSG_PREFIX_STARTED.length();
            int end = mqe.getErrorMessage().indexOf(TXT_NUM_ERROR_MSG_SUFFIX_STARTED);

            if (start >= 0 && end >= 0) {
                String numStr = mqe.getErrorMessage().substring(start, end).trim();

                return Long.parseLong(numStr);
            }
        } else if (mqe.getErrorCode() == 251 && mqe.getErrorMessage() != null) {
            int start = mqe.getErrorMessage().indexOf(TXT_NUM_ERROR_MSG_PREFIX_ABORTED)
                    + TXT_NUM_ERROR_MSG_PREFIX_ABORTED.length();
            int end = mqe.getErrorMessage().indexOf(TXT_NUM_ERROR_MSG_SUFFIX_ABORTED);

            if (start >= 0 && end >= 0) {
                String numStr = mqe.getErrorMessage().substring(start, end).trim();

                return Long.parseLong(numStr);
            }
            
            start = mqe.getErrorMessage().indexOf(TXT_NUM_ERROR_MSG_PREFIX_NONE)
                    + TXT_NUM_ERROR_MSG_PREFIX_NONE.length();
            end = mqe.getErrorMessage().indexOf(TXT_NUM_ERROR_MSG_SUFFIX_NONE);

            if (start >= 0 && end >= 0) {
                String numStr = mqe.getErrorMessage().substring(start, end).trim();

                return Long.parseLong(numStr);
            }
        } else if (mqe.getErrorCode() == 256 && mqe.getErrorMessage() != null) {
            int start = mqe.getErrorMessage().indexOf(TXT_NUM_ERROR_MSG_PREFIX_COMMITTED)
                    + TXT_NUM_ERROR_MSG_PREFIX_COMMITTED.length();
            int end = mqe.getErrorMessage().indexOf(TXT_NUM_ERROR_MSG_SUFFIX_COMMITTED);

            if (start >= 0 && end >= 0) {
                String numStr = mqe.getErrorMessage().substring(start, end).trim();

                return Long.parseLong(numStr);
            }
        }

        LOGGER.debug("***** query error not handled {}: {}",
                mqe.getErrorCode(),
                mqe.getErrorMessage());

        throw mqe;
    }

    /**
     * get txn status from error messag
     * @param mqe
     * @return 
     */
    private static TransactionState getTxnStateFromExc(MongoQueryException mqe) {
        if (mqe.getErrorCode() == 251) {
            if (mqe.getErrorMessage().contains(
                    "does not match any in-progress transactions")) {
                return TransactionState.NONE;
            } else if (mqe.getErrorMessage().contains(
                    "has been aborted")) {
                return TransactionState.ABORTED;
            }
        } else if (mqe.getErrorCode() == 256) {
            if (mqe.getErrorMessage().contains(
                    "has been committed")) {
                return TransactionState.COMMITTED;
            }
        }

        LOGGER.debug("***** query error not handled {}: {}",
                mqe.getErrorCode(),
                mqe.getErrorMessage());

        throw mqe;
    }

    public static MongoClientDelegate getMongoClientDelegate() {
        return delegate;
    }
}