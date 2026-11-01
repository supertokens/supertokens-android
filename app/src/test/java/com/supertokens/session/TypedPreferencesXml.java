package com.supertokens.session;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Android SharedPreferences XML's supported value types, without string coercion. */
final class TypedPreferencesXml {
    static Map<String, Object> read(InputStream input) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Element root = factory.newDocumentBuilder().parse(input).getDocumentElement();
        if (!root.getTagName().equals("map")) throw new IOException("Expected preferences map");
        Map<String, Object> values = new LinkedHashMap<>();
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element)) continue;
            Element item = (Element) node;
            if (!item.hasAttribute("name") || values.containsKey(item.getAttribute("name"))) {
                throw new IOException("Missing or duplicate preference name");
            }
            String type = item.getTagName();
            String value = item.getAttribute("value");
            Object parsed;
            switch (type) {
                case "null": parsed = null; break;
                case "string": parsed = item.getTextContent(); break;
                case "int": parsed = Integer.valueOf(value); break;
                case "long": parsed = Long.valueOf(value); break;
                case "float": parsed = Float.valueOf(value); break;
                case "boolean":
                    if (!value.equals("true") && !value.equals("false")) throw new IOException("Invalid boolean");
                    parsed = Boolean.valueOf(value); break;
                case "set":
                    Set<String> set = new LinkedHashSet<>();
                    for (Node child = item.getFirstChild(); child != null; child = child.getNextSibling()) {
                        if (!(child instanceof Element)) continue;
                        if (!((Element) child).getTagName().equals("string")) throw new IOException("Invalid string set");
                        set.add(child.getTextContent());
                    }
                    parsed = set; break;
                default: throw new IOException("Unsupported preference type");
            }
            values.put(item.getAttribute("name"), parsed);
        }
        return values;
    }

    static void write(Map<String, ?> values, OutputStream output) throws Exception {
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element root = document.createElement("map");
        document.appendChild(root);
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            String type;
            if (value == null) type = "null";
            else if (value instanceof String) type = "string";
            else if (value instanceof Integer) type = "int";
            else if (value instanceof Long) type = "long";
            else if (value instanceof Float) type = "float";
            else if (value instanceof Boolean) type = "boolean";
            else if (value instanceof Set) type = "set";
            else throw new IOException("Unsupported preference value");
            Element item = document.createElement(type);
            item.setAttribute("name", entry.getKey());
            root.appendChild(item);
            if (type.equals("string")) item.setTextContent((String) value);
            else if (type.equals("set")) {
                for (Object member : (Set<?>) value) {
                    if (!(member instanceof String)) throw new IOException("Invalid string set member");
                    Element child = document.createElement("string");
                    child.setTextContent((String) member);
                    item.appendChild(child);
                }
            } else if (value != null) item.setAttribute("value", String.valueOf(value));
        }
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document), new StreamResult(output));
    }
}
