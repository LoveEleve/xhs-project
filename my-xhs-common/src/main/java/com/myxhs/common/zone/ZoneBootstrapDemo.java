package com.myxhs.common.zone;

/** 临时演示文件：故意触发规范扫描（用于门禁拦截演示，稍后删除） */
public class ZoneBootstrapDemo {
    public static void main(String[] args) {
        try {
            throw new IllegalStateException("demo");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
