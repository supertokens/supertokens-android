package com.supertokens.session;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class TypedPreferencesXmlTest {
    private Map<String, Object> parse(String xml) throws Exception {
        return TypedPreferencesXml.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void preservesTypesNullEmptyAndAbsentThroughRoundTrip() throws Exception {
        Map<String, Object> values = parse("<map><int name='i' value='7'/><long name='l' value='7'/>"
                + "<float name='f' value='1.25'/><boolean name='b' value='false'/><null name='n'/>"
                + "<string name='empty'></string><string name='s'> a &amp; b </string>"
                + "<set name='set'><string>x</string><string>y</string></set></map>");
        assertEquals(Integer.class, values.get("i").getClass());
        assertEquals(Long.class, values.get("l").getClass());
        assertEquals(Float.class, values.get("f").getClass());
        assertEquals(Boolean.FALSE, values.get("b"));
        assertTrue(values.containsKey("n") && values.get("n") == null);
        assertFalse(values.containsKey("absent"));
        assertEquals("", values.get("empty"));
        assertEquals(" a & b ", values.get("s"));
        assertTrue(values.get("set") instanceof Set);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TypedPreferencesXml.write(values, bytes);
        assertEquals(values, TypedPreferencesXml.read(new ByteArrayInputStream(bytes.toByteArray())));
    }

    @Test public void ignoresMapAndSetOrder() throws Exception {
        assertEquals(parse("<map><string name='a'>x</string><set name='b'><string>1</string><string>2</string></set></map>"),
                parse("<map><set name='b'><string>2</string><string>1</string></set><string name='a'>x</string></map>"));
    }

    @Test public void rejectsMalformedOrUnsupportedTypes() throws Exception {
        for (String xml : new String[]{"<map><boolean name='b' value='truthy'/></map>",
                "<map><double name='d' value='1'/></map>", "<map><long name='l' value='x'/></map>",
                "<map><null name='x'/><string name='x'>dup</string></map>", "<map><string>unnamed</string></map>"}) {
            try { parse(xml); fail("Malformed XML was accepted"); } catch (java.io.IOException | NumberFormatException expected) { }
        }
    }
}
