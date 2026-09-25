package com.harnessrunner.gate;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class JacocoReportParser {

    public Optional<CoverageSummary> parse(Path reportFile) {
        if (reportFile == null || !Files.isRegularFile(reportFile)) {
            return Optional.empty();
        }
        try {
            Document document = XmlDocuments.parse(reportFile);
            Element report = document.getDocumentElement();
            Element line = findCounter(report, "LINE");
            if (line == null) {
                return Optional.empty();
            }
            Element branch = findCounter(report, "BRANCH");
            return Optional.of(new CoverageSummary(
                    intAttribute(line, "covered"),
                    intAttribute(line, "missed"),
                    branch == null ? 0 : intAttribute(branch, "covered"),
                    branch == null ? 0 : intAttribute(branch, "missed")));
        } catch (IOException | SAXException | ParserConfigurationException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Element findCounter(Element parent, String type) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element
                    && "counter".equals(element.getTagName())
                    && type.equals(element.getAttribute("type"))) {
                return element;
            }
        }
        return null;
    }

    private static int intAttribute(Element element, String name) {
        return Integer.parseInt(element.getAttribute(name));
    }
}
