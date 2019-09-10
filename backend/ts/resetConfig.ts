import * as express from 'express';
import * as SuperTokens from 'supertokens-node-mysql-ref-jwt/express';
import { TypeInputConfig } from 'supertokens-node-mysql-ref-jwt/lib/build/helpers/types';
import {reset} from "supertokens-node-mysql-ref-jwt/lib/build/helpers/utils";
import RefreshTokenCounter from './refreshTokenCounter';

export default async function resetConfig(req: express.Request, res: express.Response) {
    try {
        if ( req.headers.atvalidity !== undefined && typeof req.headers.atvalidity === "string") {
            let inputValidity = req.headers.atvalidity as string;
            let inputValidityInt = parseInt(inputValidity.trim());
            let newConfig: TypeInputConfig = {
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
                        validity: inputValidityInt,
                    }
                },
            }
            RefreshTokenCounter.resetRefreshTokenCount();
            await reset(newConfig);
            res.status(200).send("");
        } else {
            console.log(`Invalid parameter type provided for atvalidity. Should be string but was ${typeof req.headers.atvalidity}`)
            res.status(400).send("");
        }
    } catch (err) {
        console.log(err);
        res.status(500).send("");
    }
}