package com.myxhs.common.datagen;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机数据工厂
 * <p>
 * 生成各种随机测试数据：手机号、姓名、地址、邮箱等。
 * 所有数据均为虚构，不涉及真实用户信息。
 * </p>
 * <p>
 * 线程安全：使用 ThreadLocalRandom，多线程并发调用无竞争。
 * </p>
 */
public final class RandomDataFactory {

    private RandomDataFactory() {
    }

    // ==================== 姓名 ====================

    private static final String[] SURNAMES = {
            "赵", "钱", "孙", "李", "周", "吴", "郑", "王", "冯", "陈",
            "褚", "卫", "蒋", "沈", "韩", "杨", "朱", "秦", "尤", "许",
            "何", "吕", "施", "张", "孔", "曹", "严", "华", "金", "魏",
            "陶", "姜", "戚", "谢", "邹", "喻", "柏", "水", "窦", "章",
            "云", "苏", "潘", "葛", "奚", "范", "彭", "郎", "鲁", "韦",
            "昌", "马", "苗", "凤", "花", "方", "俞", "任", "袁", "柳",
            "丰", "鲍", "史", "唐", "费", "廉", "岑", "薛", "雷", "贺",
            "倪", "汤", "滕", "殷", "罗", "毕", "郝", "邬", "安", "常",
            "乐", "于", "时", "傅", "皮", "卞", "齐", "康", "伍", "余",
            "元", "卜", "顾", "孟", "平", "黄", "和", "穆", "萧", "尹"
    };

    private static final String[] GIVEN_NAMES = {
            "伟", "芳", "娜", "秀英", "敏", "静", "丽", "强", "磊", "洋",
            "艳", "勇", "军", "杰", "娟", "涛", "明", "超", "秀兰", "霞",
            "平", "刚", "桂英", "文", "华", "飞", "玉兰", "建华", "建国", "建军",
            "志强", "志明", "志伟", "国强", "国华", "国平", "国庆", "国栋", "国辉", "国荣",
            "小红", "小明", "小华", "小丽", "小芳", "小军", "小强", "小刚", "小伟", "小杰",
            "雪", "梅", "兰", "竹", "菊", "莲", "荷", "桃", "杏", "柳",
            "天宇", "浩然", "子轩", "梓涵", "一诺", "思远", "雨泽", "宇航", "子墨", "奕辰",
            "欣怡", "诗涵", "梦琪", "雨萱", "若曦", "语嫣", "紫萱", "可馨", "雅琴", "佳怡"
    };

    /**
     * 生成随机中文姓名
     */
    public static String chineseName() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return SURNAMES[r.nextInt(SURNAMES.length)] + GIVEN_NAMES[r.nextInt(GIVEN_NAMES.length)];
    }

    // ==================== 手机号 ====================

    private static final String[] PHONE_PREFIXES = {
            "130", "131", "132", "133", "134", "135", "136", "137", "138", "139",
            "150", "151", "152", "153", "155", "156", "157", "158", "159",
            "170", "171", "172", "173", "175", "176", "177", "178",
            "180", "181", "182", "183", "184", "185", "186", "187", "188", "189",
            "191", "198", "199"
    };

    /**
     * 生成随机手机号（11 位）
     */
    public static String phone() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String prefix = PHONE_PREFIXES[r.nextInt(PHONE_PREFIXES.length)];
        return prefix + String.format("%08d", r.nextInt(100_000_000));
    }

    // ==================== 邮箱 ====================

    private static final String[] EMAIL_DOMAINS = {
            "qq.com", "163.com", "126.com", "gmail.com", "outlook.com",
            "sina.com", "sohu.com", "foxmail.com", "hotmail.com", "yeah.net"
    };

    /**
     * 生成随机邮箱
     */
    public static String email(long id) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "user" + id + "@" + EMAIL_DOMAINS[r.nextInt(EMAIL_DOMAINS.length)];
    }

    // ==================== 地址 ====================

    private static final String[] PROVINCES = {
            "北京市", "上海市", "广东省", "浙江省", "江苏省", "四川省", "湖北省",
            "山东省", "河南省", "福建省", "湖南省", "安徽省", "河北省", "陕西省",
            "重庆市", "天津市", "辽宁省", "吉林省", "黑龙江省", "江西省"
    };

    private static final String[] CITIES = {
            "市辖区", "杭州市", "南京市", "成都市", "武汉市", "深圳市", "广州市",
            "苏州市", "无锡市", "宁波市", "青岛市", "济南市", "郑州市", "长沙市",
            "福州市", "厦门市", "合肥市", "西安市", "大连市", "沈阳市"
    };

    private static final String[] DISTRICTS = {
            "朝阳区", "海淀区", "浦东新区", "天河区", "西湖区", "武侯区",
            "江汉区", "鼓楼区", "雨花台区", "锦江区", "南山区", "福田区",
            "余杭区", "滨江区", "高新区", "经开区", "新城区", "碑林区"
    };

    private static final String[] STREETS = {
            "中山路", "人民路", "解放路", "建设路", "和平路", "长安街",
            "南京路", "北京路", "上海路", "广州路", "深圳路", "杭州路",
            "科技大道", "创新路", "互联网大道", "数码路", "软件园路", "产业园路"
    };

    /**
     * 生成随机地址
     */
    public static String address() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return PROVINCES[r.nextInt(PROVINCES.length)]
                + CITIES[r.nextInt(CITIES.length)]
                + DISTRICTS[r.nextInt(DISTRICTS.length)]
                + STREETS[r.nextInt(STREETS.length)]
                + (r.nextInt(200) + 1) + "号"
                + (r.nextInt(30) + 1) + "栋"
                + (r.nextInt(30) + 1) + "0" + (r.nextInt(4) + 1) + "室";
    }

    /**
     * 生成随机省份
     */
    public static String province() {
        return PROVINCES[ThreadLocalRandom.current().nextInt(PROVINCES.length)];
    }

    /**
     * 生成随机城市
     */
    public static String city() {
        return CITIES[ThreadLocalRandom.current().nextInt(CITIES.length)];
    }

    /**
     * 生成随机区县
     */
    public static String district() {
        return DISTRICTS[ThreadLocalRandom.current().nextInt(DISTRICTS.length)];
    }

    // ==================== 笔记/商品 ====================

    private static final String[] NOTE_TITLES = {
            "今日穿搭分享", "美食探店", "旅行日记", "护肤心得", "健身打卡",
            "读书笔记", "家居好物", "数码测评", "宠物日常", "职场干货",
            "减脂餐分享", "化妆教程", "穿搭灵感", "咖啡推荐", "电影推荐",
            "音乐分享", "摄影技巧", "手工DIY", "育儿经验", "学习方法"
    };

    private static final String[] PRODUCT_NAMES = {
            "纯棉T恤", "牛仔裤", "运动鞋", "双肩包", "手机壳",
            "蓝牙耳机", "保温杯", "面膜", "洗面奶", "防晒霜",
            "口红", "眼影盘", "香水", "手表", "项链",
            "零食大礼包", "坚果礼盒", "咖啡豆", "茶叶", "巧克力"
    };

    /**
     * 生成随机笔记标题
     */
    public static String noteTitle() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return NOTE_TITLES[r.nextInt(NOTE_TITLES.length)] + " #" + r.nextInt(10000);
    }

    /**
     * 生成随机商品名称
     */
    public static String productName() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return PRODUCT_NAMES[r.nextInt(PRODUCT_NAMES.length)] + " " + (2024 + r.nextInt(3)) + "新款";
    }

    // ==================== 通用 ====================

    /**
     * 生成随机头像 URL
     */
    public static String avatar(long id) {
        return "https://cdn.myxhs.com/avatar/" + (id % 1000) + ".jpg";
    }

    /**
     * 生成随机图片 URL
     */
    public static String imageUrl(long id) {
        return "https://cdn.myxhs.com/img/" + (id % 10000) + ".jpg";
    }

    /**
     * 生成随机价格（1.00 ~ 9999.99）
     */
    public static double price() {
        return Math.round(ThreadLocalRandom.current().nextDouble(1.0, 10000.0) * 100) / 100.0;
    }

    /**
     * 生成随机库存（10 ~ 99999）
     */
    public static int stock() {
        return ThreadLocalRandom.current().nextInt(10, 100000);
    }

    /**
     * 帕累托分布：模拟 20/80 法则
     * <p>
     * 1% 的 ID 被选中的概率远高于其他 ID。
     * 用于模拟热门用户、热门商品等场景。
     * </p>
     *
     * @param maxId 最大 ID
     * @param alpha 帕累托指数（越大越集中，推荐 1.5~2.0）
     * @return 随机 ID（1 ~ maxId）
     */
    public static long paretoId(long maxId, double alpha) {
        double u = ThreadLocalRandom.current().nextDouble();
        // 帕累托逆变换：x = maxId * (1 - u)^(-1/alpha)，截断到 [1, maxId]
        long id = (long) (maxId * Math.pow(1 - u, -1.0 / alpha));
        return Math.max(1, Math.min(id, maxId));
    }

    /**
     * 生成随机时间戳（过去 365 天内）
     * <p>
     * 时间分布：工作日 vs 周末 = 5:2，高峰时段（10-12, 19-22）概率更高。
     * </p>
     */
    public static java.time.LocalDateTime randomDateTime() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        // 随机天数（过去 365 天）
        int daysAgo = r.nextInt(365);
        // 随机小时（高峰时段概率更高）
        int hour = randomHour();
        int minute = r.nextInt(60);
        int second = r.nextInt(60);
        return now.minusDays(daysAgo).withHour(hour).withMinute(minute).withSecond(second).withNano(0);
    }

    /**
     * 随机小时（模拟真实用户行为分布）
     * <p>
     * 高峰时段（10-12, 19-22）概率 60%，其他时段 40%。
     * 凌晨（0-6）概率极低。
     * </p>
     */
    private static int randomHour() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double p = r.nextDouble();
        if (p < 0.05) return r.nextInt(0, 7);       // 凌晨 0-6：5%
        if (p < 0.20) return r.nextInt(7, 10);       // 早间 7-9：15%
        if (p < 0.40) return r.nextInt(10, 13);      // 上午高峰 10-12：20%
        if (p < 0.55) return r.nextInt(13, 17);      // 下午 13-16：15%
        if (p < 0.65) return r.nextInt(17, 19);      // 傍晚 17-18：10%
        if (p < 0.90) return r.nextInt(19, 23);      // 晚间高峰 19-22：25%
        return r.nextInt(23, 24);                     // 深夜 23：10%
    }
}
