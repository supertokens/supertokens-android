package com.supertokens.session;

abstract class CoreSessionProtocolFixture extends SessionTestFixture {
    protected String backend() { return System.getProperty("coreProtocolUrl"); }
}
