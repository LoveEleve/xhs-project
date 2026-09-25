package com.harnessrunner.gate;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class PitReportParser {

    public Optional<MutationSummary> parse(Path reportFile) {
        if (reportFile == null || !Files.isRegularFile(reportFile)) {
            return Optional.empty();
        }
        try {
            Document document = XmlDocuments.parse(reportFile);
            NodeList mutations = document.getDocumentElement().getElementsByTagName("mutation");
            int killed = 0;
            int survived = 0;
            int noCoverage = 0;
            for (int i = 0; i < mutations.getLength(); i++) {
                String status = ((Element) mutations.item(i)).getAttribute("status");
                switch (status) {
                    case "KILLED" -> killed++;
                    case "SURVIVED" -> survived++;
                    case "NO_COVERAGE" -> noCoverage++;
                    default -> {
                    }
                }
            }
            return Optional.of(new MutationSummary(killed, survived, noCoverage));
        } catch (IOException | SAXException | ParserConfigurationException e) {
            return Optional.empty();
        }
    }
}
