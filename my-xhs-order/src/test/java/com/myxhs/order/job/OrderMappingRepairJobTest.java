package com.myxhs.order.job;

import com.myxhs.order.entity.Order;
import com.myxhs.order.entity.OrderNoMapping;
import com.myxhs.order.mapper.OrderMapper;
import com.myxhs.order.repository.OrderNoMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderMappingRepairJobTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderNoMappingRepository orderNoMappingRepository;

    private OrderMappingRepairJob job;

    @BeforeEach
    void setUp() {
        job = new OrderMappingRepairJob(orderMapper, orderNoMappingRepository);
    }

    @Test
    void shouldRepairOrdersOlderThanOneHourWithinLookbackWindow() {
        Order oldOrder = new Order();
        oldOrder.setId(123456L);
        oldOrder.setOrderNo("ORD20250101000000001");
        oldOrder.setUserId(1001L);
        oldOrder.setCreatedAt(LocalDateTime.now().minusDays(120));

        when(orderMapper.selectOrdersForMappingRepair(eq(0L), eq(200)))
                .thenReturn(List.of(oldOrder));
        when(orderNoMappingRepository.selectByOrderNo("ORD20250101000000001")).thenReturn(null);

        job.repairMappings();

        verify(orderNoMappingRepository).insert(any(OrderNoMapping.class));
    }

    @Test
    void shouldSkipExistingMapping() {
        Order order = new Order();
        order.setId(123456L);
        order.setOrderNo("ORD20250101000000001");
        order.setUserId(1001L);

        when(orderMapper.selectOrdersForMappingRepair(eq(0L), eq(200)))
                .thenReturn(List.of(order));
        when(orderNoMappingRepository.selectByOrderNo("ORD20250101000000001")).thenReturn(new OrderNoMapping());

        job.repairMappings();

        verify(orderNoMappingRepository, never()).insert(any(OrderNoMapping.class));
    }
}
