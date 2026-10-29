# Published SDK compatibility fixture

`../resources/compatibility/OldSdkConsumer.bin` is Java 8 bytecode compiled from
`OldSdkConsumer.java` against the published JitPack artifact
`com.github.supertokens:supertokens-android:0.5.4`, not the current source.
The AAR's SHA-256 is `2f3b4a94a036d21ced610f9c41707d7e1ccf7c9c0256e47e5686c1c76b8224b9`.

To regenerate it, provide the published AAR and an Android SDK containing API 32:

```sh
app/src/test/compatibility/regenerate.sh /path/to/supertokens-android-0.5.4.aar
```

The normal test loads the checked-in bytecode against the candidate SDK; it does
not compile the consumer against the candidate. Review fixture changes carefully,
as recompilation changes which historical API signatures the test exercises.
