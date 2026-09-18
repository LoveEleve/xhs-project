package com.myxhs.common.zone.locator;

import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网段 Zone 定位器：配置"CIDR=zone"映射，匹配本机任一网卡地址。
 * <p>示例：192.168.0.0/24=zone-a,10.0.0.0/8=zone-b</p>
 *
 * @since 1.0.0
 */
@Slf4j
public class IpRangeZoneLocator implements ZoneLocator {

    private final Map<String, String> cidrToZone;

    public IpRangeZoneLocator(List<String> mappings) {
        this.cidrToZone = new LinkedHashMap<>();
        if (mappings != null) {
            for (String mapping : mappings) {
                if (mapping == null || !mapping.contains("=")) {
                    continue;
                }
                String[] parts = mapping.split("=", 2);
                cidrToZone.put(parts[0].trim(), parts[1].trim());
            }
        }
    }

    @Override
    public String locate() {
        if (cidrToZone.isEmpty()) {
            return null;
        }
        List<InetAddress> addresses = localAddresses();
        for (Map.Entry<String, String> entry : cidrToZone.entrySet()) {
            for (InetAddress address : addresses) {
                if (matches(address, entry.getKey())) {
                    log.info("[ZoneLocator] 从网段发现 Zone: ip={}, cidr={}, zone={}",
                            address.getHostAddress(), entry.getKey(), entry.getValue());
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private List<InetAddress> localAddresses() {
        List<InetAddress> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    result.add(addrs.nextElement());
                }
            }
        } catch (Exception e) {
            log.debug("[ZoneLocator] 枚举网卡失败: {}", e.getMessage());
        }
        return result;
    }

    static boolean matches(InetAddress address, String cidr) {
        if (address == null || cidr == null || !(address instanceof Inet4Address)) {
            return false;
        }
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                return false;
            }
            InetAddress network = InetAddress.getByName(parts[0].trim());
            int prefix = Integer.parseInt(parts[1].trim());
            if (!(network instanceof Inet4Address) || prefix < 0 || prefix > 32) {
                return false;
            }
            byte[] a = address.getAddress();
            byte[] b = network.getAddress();
            int fullBytes = prefix / 8;
            int remainder = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (a[i] != b[i]) {
                    return false;
                }
            }
            if (remainder > 0) {
                int mask = 0xFF << (8 - remainder);
                return (a[fullBytes] & mask) == (b[fullBytes] & mask);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public int getOrder() {
        return 30;
    }
}
