const http = require('http');

({
    options: {
        name: "nodePromiseSrv",
        description: "just an example node service that requires http and returns a promise",
        uri: '/promise',
        secured: false, // optional, default false
        matchPolicy: "PREFIX" // optional, default PREFIX
    },

    handle: (request, response) => new Promise((resolve, reject) => {
        // LOGGER.debug('request {}', request.getContent());
        const reqOpts = {
            hostname: 'httpbin.org',
            port: 80,
            path: '/anything',
            method: 'GET'
        }

        const req = http.request(reqOpts, res => {
            let data = '';

            res.on('data', d => {
                data += d;
            });

            res.on('end', () => {
                const rc = JSON.parse(request.getContent() || '{}');

                let body = {
                    msg: `Hello ${rc.name || 'World'}`,
                    anything: JSON.parse(data)
                };

                response.setContent(JSON.stringify(body));
                response.setContentTypeAsJson();

                resolve();
            });
        })

        req.on("error", (err) => reject(err));

        req.end();
    })
})