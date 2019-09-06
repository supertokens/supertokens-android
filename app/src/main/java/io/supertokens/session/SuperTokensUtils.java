package io.supertokens.session;

import java.io.IOException;

class SuperTokensUtils {
    static class Unauthorised {
        UnauthorisedStatus status;
        IOException error;

        enum UnauthorisedStatus {
            SESSION_EXPIRED,
            API_ERROR,
            RETRY,
        }

        Unauthorised(UnauthorisedStatus status) {
            this.status = status;
        }

        Unauthorised(UnauthorisedStatus status, IOException error) {
            this.status = status;
            this.error = error;
        }
    }
}
