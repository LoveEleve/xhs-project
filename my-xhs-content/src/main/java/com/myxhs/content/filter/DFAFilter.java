package com.myxhs.content.filter;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import com.myxhs.common.constants.RedisKeyConstants;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * DFA 敏感词过滤器
 * <p>
 * 核心原理：Trie 树（前缀树）构建一次，匹配 O(n) 时间复杂度。
 * vs SQL LIKE：10万词库 × LIKE 查询 = 10万次扫描，DFA 只需遍历文本1遍。
 * </p>
 * <p>
 * 文本预处理：匹配前去除空格、特殊字符、全角转半角，防止用户通过插入字符绕过检测。
 * </p>
 */
@Slf4j
@Component
public class DFAFilter implements MessageListener {

    /** Trie 树根节点 */
    private volatile Map<Character, Object> trieRoot = new HashMap<>();

    /** 结束标记字符 */
    private static final char END_FLAG = '\0';

    /** Redis 敏感词更新通知 Channel */
    private static final String SENSITIVE_WORD_CHANNEL = RedisKeyConstants.PROJECT_PREFIX + "sensitive-word:reload";

    /** Redis 中存储的动态敏感词 Key */
    private static final String SENSITIVE_WORD_REDIS_KEY = RedisKeyConstants.PROJECT_PREFIX + "sensitive-word:list";

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Autowired(required = false)
    private RedisMessageListenerContainer redisMessageListenerContainer;

    /**
     * 启动时从 classpath 加载敏感词库并构建 Trie 树，同时注册 Redis 监听
     */
    @PostConstruct
    public void init() {
        // 1. 加载静态词库（classpath 文件）
        List<String> words = loadWordsFromFile();

        // 2. 加载动态词库（Redis 中运营后台添加的）
        List<String> dynamicWords = loadWordsFromRedis();
        words.addAll(dynamicWords);

        // 3. 构建 Trie 树
        buildTrie(words);

        // 4. 注册 Redis Pub/Sub 监听（多实例广播通知）
        registerRedisListener();
    }

    /**
     * Redis Pub/Sub 消息回调 — 收到通知后重建 Trie 树
     * <p>
     * 当运营后台添加/删除敏感词后，通过 Redis PUBLISH 通知所有实例重建。
     * 所有实例都会收到消息并重建，保证多实例词库一致。
     * </p>
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        log.info("[DFA] 收到敏感词更新通知，开始重建 Trie 树");
        reload();
    }

    /**
     * 重新加载敏感词库并重建 Trie 树
     * <p>
     * 可被外部调用（如管理接口），也可被 Redis Pub/Sub 触发。
     * </p>
     */
    public void reload() {
        List<String> words = loadWordsFromFile();
        List<String> dynamicWords = loadWordsFromRedis();
        words.addAll(dynamicWords);
        buildTrie(words);
        log.info("[DFA] 敏感词 Trie 树重建完成，总词库大小: {}", words.size());
    }

    /**
     * 添加动态敏感词（存入 Redis + 广播通知所有实例重建）
     *
     * @param words 要添加的敏感词列表
     */
    public void addDynamicWords(List<String> words) {
        if (stringRedisTemplate == null || words == null || words.isEmpty()) {
            return;
        }
        // 存入 Redis Set
        stringRedisTemplate.opsForSet().add(SENSITIVE_WORD_REDIS_KEY, words.toArray(new String[0]));
        // 广播通知所有实例重建
        stringRedisTemplate.convertAndSend(SENSITIVE_WORD_CHANNEL, "reload");
        log.info("[DFA] 添加动态敏感词 {} 个，已广播通知", words.size());
    }

    /**
     * 删除动态敏感词（从 Redis 移除 + 广播通知所有实例重建）
     *
     * @param words 要删除的敏感词列表
     */
    public void removeDynamicWords(List<String> words) {
        if (stringRedisTemplate == null || words == null || words.isEmpty()) {
            return;
        }
        stringRedisTemplate.opsForSet().remove(SENSITIVE_WORD_REDIS_KEY, words.toArray(new Object[0]));
        stringRedisTemplate.convertAndSend(SENSITIVE_WORD_CHANNEL, "reload");
        log.info("[DFA] 删除动态敏感词 {} 个，已广播通知", words.size());
    }

    /**
     * 从 Redis 加载动态敏感词
     */
    private List<String> loadWordsFromRedis() {
        List<String> words = new ArrayList<>();
        if (stringRedisTemplate == null) {
            return words;
        }
        try {
            Set<String> members = stringRedisTemplate.opsForSet().members(SENSITIVE_WORD_REDIS_KEY);
            if (members != null) {
                words.addAll(members);
            }
            if (!words.isEmpty()) {
                log.info("[DFA] 从 Redis 加载动态敏感词 {} 个", words.size());
            }
        } catch (Exception e) {
            log.warn("[DFA] 从 Redis 加载动态敏感词失败（不影响静态词库）: {}", e.getMessage());
            // metrics 告警：动态词库失效需运维介入
        }
        return words;
    }

    /**
     * 注册 Redis Pub/Sub 监听
     */
    private void registerRedisListener() {
        if (redisMessageListenerContainer == null) {
            log.info("[DFA] RedisMessageListenerContainer 未配置，跳过 Pub/Sub 监听注册");
            return;
        }
        redisMessageListenerContainer.addMessageListener(this, new ChannelTopic(SENSITIVE_WORD_CHANNEL));
        log.info("[DFA] 已注册 Redis Pub/Sub 监听, channel={}", SENSITIVE_WORD_CHANNEL);
    }

    /**
     * 从 classpath 加载敏感词文件
     */
    private List<String> loadWordsFromFile() {
        List<String> words = new ArrayList<>();
        try {
            ClassPathResource resource = new ClassPathResource("sensitive-words.txt");
            if (!resource.exists()) {
                log.warn("[DFA] 敏感词文件 sensitive-words.txt 不存在，使用空词库");
                return words;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        words.add(line);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[DFA] 加载敏感词文件失败", e);
        }
        return words;
    }

    /**
     * 构建 Trie 树
     */
    @SuppressWarnings("unchecked")
    public void buildTrie(List<String> words) {
        Map<Character, Object> root = new HashMap<>();
        for (String word : words) {
            if (word == null || word.isEmpty()) {
                continue;
            }
            Map<Character, Object> current = root;
            for (int i = 0; i < word.length(); i++) {
                char c = word.charAt(i);
                current = (Map<Character, Object>) current.computeIfAbsent(c, k -> new HashMap<>());
            }
            current.put(END_FLAG, null); // 结束标记
        }
        this.trieRoot = root; // volatile 保证可见性
        log.info("[DFA] 敏感词 Trie 树构建完成，词库大小: {}", words.size());
    }

    /**
     * 检测文本中的敏感词
     *
     * @param text 待检测文本
     * @return 命中的敏感词集合（空集合表示通过）
     */
    @SuppressWarnings("unchecked")
    public Set<String> detect(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return result;
        }

        // 文本预处理：去除空格、特殊字符、全角转半角、统一小写
        String processed = preprocess(text);

        for (int i = 0; i < processed.length(); i++) {
            Map<Character, Object> current = trieRoot;
            StringBuilder word = new StringBuilder();
            for (int j = i; j < processed.length(); j++) {
                char c = processed.charAt(j);
                Object next = current.get(c);
                if (next == null) {
                    break; // 不匹配，跳出
                }
                word.append(c);
                if (next instanceof Map) {
                    current = (Map<Character, Object>) next;
                    if (current.containsKey(END_FLAG)) {
                        result.add(word.toString()); // 命中敏感词
                    }
                }
            }
        }
        return result;
    }

    /**
     * 检测文本是否包含敏感词
     *
     * @return true=包含敏感词
     */
    public boolean containsSensitiveWord(String text) {
        return !detect(text).isEmpty();
    }

    /**
     * 文本预处理
     * <p>
     * 1. 全角转半角
     * 2. 去除空格和特殊字符
     * 3. 统一小写
     * </p>
     */
    private String preprocess(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 全角转半角
            if (c >= 0xFF01 && c <= 0xFF5E) {
                c = (char) (c - 0xFEE0);
            } else if (c == 0x3000) {
                c = ' ';
            }
            // 跳过空格和常见干扰字符
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r'
                    || c == '*' || c == '#' || c == '@' || c == '!'
                    || c == '.' || c == ',' || c == '。' || c == '，') {
                continue;
            }
            // 统一小写
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * 获取当前词库大小（用于监控）
     */
    public int getWordCount() {
        return countWords(trieRoot);
    }

    @SuppressWarnings("unchecked")
    private int countWords(Map<Character, Object> node) {
        int count = 0;
        for (Map.Entry<Character, Object> entry : node.entrySet()) {
            if (entry.getKey() == END_FLAG) {
                count++;
            } else if (entry.getValue() instanceof Map) {
                count += countWords((Map<Character, Object>) entry.getValue());
            }
        }
        return count;
    }
}
