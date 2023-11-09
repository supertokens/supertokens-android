import ThirdParty from "supertokens-node/recipe/thirdparty";
import Session from "supertokens-node/recipe/session";
import { TypeInput } from "supertokens-node/types";
import Dashboard from "supertokens-node/recipe/dashboard";

export function getApiDomain() {
    const apiPort = process.env.REACT_APP_API_PORT || 3001;
    const apiUrl = process.env.REACT_APP_API_URL || `http://localhost:${apiPort}`;
    return apiUrl;
}

export function getWebsiteDomain() {
    const websitePort = process.env.REACT_APP_WEBSITE_PORT || 3000;
    const websiteUrl = process.env.REACT_APP_WEBSITE_URL || `http://localhost:${websitePort}`;
    return websiteUrl;
}

export const SuperTokensConfig: TypeInput = {
    supertokens: {
        // this is the location of the SuperTokens core.
        connectionURI: "https://try.supertokens.com",
    },
    appInfo: {
        appName: "SuperTokens Demo App",
        apiDomain: getApiDomain(),
        websiteDomain: getWebsiteDomain(),
    },
    // recipeList contains all the modules that you want to
    // use from SuperTokens. See the full list here: https://supertokens.com/docs/guides
    recipeList: [
        ThirdParty.init({
            signInAndUpFeature: {
                providers: [
                    // We have provided you with development keys which you can use for testing.
                    // IMPORTANT: Please replace them with your own OAuth keys for production use.
                    {
                        config: {
                            thirdPartyId: "google",
                            clients: [
                                {
                                    clientId:
                                        "GOOGLE_WEB_CLIENT_ID",
                                    clientSecret: "GOOGLE_WEB_CLIENT_SECRET",
                                },
                            ],
                        },
                    },
                    {
                        config: {
                            thirdPartyId: "github",
                            clients: [
                                {
                                    clientId: "GITHUB_CLIENT_ID",
                                    clientSecret: "GITHUB_CLIENT_SECRET",
                                },
                            ],
                        },
                    },
                ],
            },
        }),
        Session.init(),
        Dashboard.init(),
    ],
};
