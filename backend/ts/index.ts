import * as cookieParser from 'cookie-parser';
import * as express from 'express';
import * as http from 'http';
import * as SuperTokens from 'supertokens-node-mysql-ref-jwt/express';

import login from './login';
import logout from './logout';
import refreshtoken from './refreshtoken';
import userInfo from './userInfo';

let app = express();
app.use(cookieParser());
SuperTokens.init({
    cookie: {
        domain: "192.168.29.145",
        secure: false
    },
    mysql: {
        password: "root",
        user: "root",
        database: "auth_session"
    },
    tokens: {
        refreshToken: {
            renewTokenPath: "/api/refreshtoken"
        },
        accessToken: {
            validity: 10
        }
    },
}).then(() => {
    initRoutesAndServer();
}).catch((err: any) => {
    console.log("error while initing auth service!", err);
});

function initRoutesAndServer() {
    app.post("/api/login", function (req, res) {
        login(req, res).catch(err => {
            console.log(err);
            res.status(500).send("");
        });
    });

    app.get("/api/userInfo", function (req, res) {
        userInfo(req, res).catch(err => {
            console.log(err);
            res.status(500).send("");
        });
    });

    app.post("/api/refreshtoken", function (req, res) {
        refreshtoken(req, res).catch(err => {
            console.log(err);
            res.status(500).send("");
        });
    });

    app.post("/api/logout", function (req, res) {
        logout(req, res).catch(err => {
            console.log(err);
            res.status(500).send("");
        });
    });

    app.use("*", function (req, res, next) {
        res.status(404).send("Not found");
    });

    let server = http.createServer(app);
    server.listen(8080, "0.0.0.0");
}
