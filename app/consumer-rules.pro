# Persisted ObjectOutputStream cookies must reopen after SDK upgrades and R8 remapping.
-keep class com.supertokens.session.SuperTokensSerializableHttpCookie {
    private static final long serialVersionUID;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}
