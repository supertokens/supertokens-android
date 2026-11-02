# SuperTokens Android SDK

<a href="https://supertokens.com/discord">
<img src="https://img.shields.io/discord/603466164219281420.svg?logo=discord"
    alt="chat on Discord"></a>

## About

This is an Android SDK that is responsible for maintaining a SuperTokens session for an Android app.

Learn more at https://supertokens.com

## Documentation

To see documentation, please click [here](https://supertokens.com/docs/guides).

### 0.6.0 candidate

Existing 0.5.4 integrations require no code or stored-session migration changes.
The additive [session ownership APIs](SESSION_OWNERSHIP.md) support conditional
session updates, request fencing and observation; adopting them is optional.
The guide describes the behavior contract, not release certification. Current
qualification and remaining release gates are tracked in the
[qualification summary](docs/COMPATIBILITY_STATUS.md).

The library preserves Java 7 production bytecode and `minSdkVersion 17`.
Choose an OkHttp runtime compatible with your Android targets; the AAR does not
bundle it. Only the new opt-in `SuperTokensInterceptor(baseClient)` refresh
transport requires OkHttp 3.14+. This does not raise the legacy constructor's
dependency floor. Declared support and JVM linkage checks are not device-runtime
certification.

Refresh flights are shared across transports; HTTP exchange counts depend on the
leading transport. The legacy no-arg interceptor preserves caller retries and
follow-ups. Optional owned refresh limits each transport execution to at most one
HTTP exchange; it does not promise exactly-once processing or override a legacy
flight leader. See the [approved transport contract](SESSION_OWNERSHIP.md#transport-policy).

## Contributing

Please refer to the [CONTRIBUTING.md](https://github.com/supertokens/supertokens-android/blob/master/CONTRIBUTING.md) file in this repo.

## Contact us

For any queries, or support requests, please email us at team@supertokens.com, or join our [Discord](supertokens.com/discord) server.

## Authors

Created with :heart: by the folks at SuperTokens.com.
