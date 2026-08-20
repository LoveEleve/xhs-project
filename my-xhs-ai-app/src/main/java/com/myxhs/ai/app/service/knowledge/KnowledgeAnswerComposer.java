package com.myxhs.ai.app.service.knowledge;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 第一版知识回答组装器：主卡 + 最多 2 张辅卡。 */
@Component
public class KnowledgeAnswerComposer {

    public String compose(String question, List<KnowledgeCard> cards) {
        if (cards.isEmpty()) {
            return "我当前还没有足够的系统知识卡来稳定回答这个问题。";
        }
        cards = new ArrayList<>(cards);
        cards.sort(Comparator.comparingInt(this::priorityRank));
        KnowledgeCard main = cards.get(0);
        List<KnowledgeCard> support = cards.subList(1, Math.min(cards.size(), 3));

        StringBuilder sb = new StringBuilder();
        sb.append(main.answer()).append("\n\n");
        if (!main.structuredPoints().isEmpty()) {
            sb.append("关键点：\n");
            main.structuredPoints().forEach((k,v) -> sb.append("- ").append(v).append("\n"));
            sb.append("\n");
        }
        if (!support.isEmpty()) {
            sb.append("补充说明：\n");
            for (KnowledgeCard c : support) {
                sb.append("- ").append(c.answer()).append("\n");
            }
            sb.append("\n");
        }
        if (!main.antiConfusion().isEmpty()) {
            sb.append("容易混淆的点：\n");
            for (String s : main.antiConfusion()) sb.append("- ").append(s).append("\n");
            sb.append("\n");
        }
        if (!main.followupDocs().isEmpty()) {
            sb.append("如果要继续深挖，可再看：\n");
            for (String s : main.followupDocs()) sb.append("- ").append(s).append("\n");
        }
        return sb.toString().trim();
    }

    private int priorityRank(KnowledgeCard c) {
        return switch (c.priority()) {
            case "P0" -> 0;
            case "P1" -> 1;
            case "P2" -> 2;
            default -> 3;
        };
    }
}
