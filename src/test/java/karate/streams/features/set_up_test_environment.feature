@ignore
Feature: Opens Change Stream resources

Background:
* url 'http://localhost:18080'
* def db = '/test-change-streams'
* def coll = db + '/coll'
# note: db starting with 'test-' are automatically deleted after test finishes

@requires-mongodb-3.6 @requires-replica-set
Scenario: Setup test environment

# Step 1: Create test database
    Given path db
    And request {}
    When method PUT
    Then status 201

# Step 2: Create test collection
    Given path coll
    And request {"streams": [{"stages": [], "uri": "changeStream" }, {"stages": [{"_$match": {"fullDocument.targettedProperty": {"_$var": "param"}}}], "uri": "changeStreamWithStageParam" }]}
    When method PUT
    Then status 201