import * as express from 'express';
import RefreshTokenCounter from './refreshTokenCounter';

export async function testGetRefreshCounter(req: express.Request, res: express.Response) {
    console.log("GET COUNTER", RefreshTokenCounter.refreshTokenCounter)
    res.status(200).send(JSON.stringify({counter: RefreshTokenCounter.refreshTokenCounter}));
}