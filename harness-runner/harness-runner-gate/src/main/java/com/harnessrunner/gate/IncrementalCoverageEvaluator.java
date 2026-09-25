package com.harnessrunner.gate;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IncrementalCoverageEvaluator {

    public IncrementalCoverage evaluate(Path jacocoReport, Map<String, List<LineRange>> changedLines) {
        Map<String, Map<Integer, Boolean>> lineIndex = lineIndex(jacocoReport);
        int executable = 0;
        int covered = 0;
        for (Map.Entry<String, List<LineRange>> changed : changedLines.entrySet()) {
            Map<Integer, Boolean> fileLines = resolve(lineIndex, changed.getKey());
            if (fileLines == null) {
                continue;
            }
            for (LineRange range : changed.getValue()) {
                for (int line = range.start(); line <= range.end(); line++) {
                    Boolean lineCovered = fileLines.get(line);
                    if (lineCovered != null) {
                        executable++;
                        if (lineCovered) {
                            covered++;
                        }
                    }
                }
            }
        }
        return new IncrementalCoverage(executable, covered);
    }

    private static Map<Integer, Boolean> resolve(Map<String, Map<Integer, Boolean>> index, String diffPath) {
        Map<Integer, Boolean> exact = index.get(diffPath);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Map<Integer, Boolean>> entry : index.entrySet()) {
            String candidate = entry.getKey();
            if (diffPath.endsWith("/" + candidate)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, Map<Integer, Boolean>> lineIndex(Path jacocoReport) {
        Map<String, Map<Integer, Boolean>> index = new LinkedHashMap<>();
        try {
            Document document = XmlDocuments.parse(jacocoReport);
            NodeList sourceFiles = document.getElementsByTagName("sourcefile");
            for (int i = 0; i < sourceFiles.getLength(); i++) {
                Element sourceFile = (Element) sourceFiles.item(i);
                Map<Integer, Boolean> lines = index.computeIfAbsent(keyOf(sourceFile),
                        key -> new LinkedHashMap<>());
                NodeList lineNodes = sourceFile.getElementsByTagName("line");
                for (int j = 0; j < lineNodes.getLength(); j++) {
                    Element line = (Element) lineNodes.item(j);
                    int nr = Integer.parseInt(line.getAttribute("nr"));
                    boolean covered = Integer.parseInt(line.getAttribute("ci")) > 0;
                    lines.merge(nr, covered, Boolean::logicalOr);
                }
            }
        } catch (IOException | SAXException | ParserConfigurationException | NumberFormatException e) {
            throw new IllegalStateException("解析 JaCoCo 行级覆盖失败: " + jacocoReport, e);
        }
        return index;
    }

    private static String keyOf(Element sourceFile) {
        String name = sourceFile.getAttribute("name");
        Node parent = sourceFile.getParentNode();
        String packageName = parent instanceof Element element && "package".equals(element.getTagName())
                ? element.getAttribute("name")
                : "";
        return packageName == null || packageName.isBlank() ? name : packageName + "/" + name;
    }
}
