package com.myxhs.home.controller;

import com.myxhs.home.service.CartAggService;
import com.myxhs.home.service.FeedService;
import com.myxhs.home.service.NoteAggService;
import com.myxhs.home.service.ProductAggService;
import com.myxhs.home.service.UserProfileAggService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class HomeControllerTest {

    @Mock
    private FeedService feedService;
    @Mock
    private NoteAggService noteAggService;
    @Mock
    private ProductAggService productAggService;
    @Mock
    private UserProfileAggService userProfileAggService;
    @Mock
    private CartAggService cartAggService;

    private MockMvc mockMvc;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newSingleThreadExecutor();
        HomeController controller = new HomeController(feedService, noteAggService, productAggService,
                userProfileAggService, cartAggService, executorService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void productDownstreamUnavailableShouldReturnServiceUnavailable() throws Exception {
        when(productAggService.getProductDetail(1L))
                .thenThrow(new com.myxhs.home.exception.DownstreamUnavailableException("商品服务不可用"));

        org.springframework.test.web.servlet.MvcResult mvcResult = mockMvc.perform(get("/api/home/product/1"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("商品服务不可用"));
    }

    @Test
    void userDownstreamUnavailableShouldReturnServiceUnavailable() throws Exception {
        when(userProfileAggService.getUserProfile(1L, null))
                .thenThrow(new com.myxhs.home.exception.DownstreamUnavailableException("用户服务不可用"));

        org.springframework.test.web.servlet.MvcResult mvcResult = mockMvc.perform(get("/api/home/user/1"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("用户服务不可用"));
    }

    @Test
    void cartDownstreamUnavailableShouldReturnServiceUnavailable() throws Exception {
        when(cartAggService.getCartAgg(1001L))
                .thenThrow(new com.myxhs.home.exception.DownstreamUnavailableException("购物车服务不可用"));

        org.springframework.test.web.servlet.MvcResult mvcResult = mockMvc.perform(get("/api/home/cart").header("X-User-Id", 1001L))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("购物车服务不可用"));
    }

    @Test
    void noteDownstreamUnavailableShouldReturnServiceUnavailable() throws Exception {
        when(noteAggService.getNoteDetail(1L, null))
                .thenThrow(new com.myxhs.home.exception.DownstreamUnavailableException("内容服务不可用"));

        org.springframework.test.web.servlet.MvcResult mvcResult = mockMvc.perform(get("/api/home/note/1"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("内容服务不可用"));
    }

    @Test
    void feedDownstreamUnavailableShouldReturnServiceUnavailable() throws Exception {
        when(feedService.getFollowFeed(1001L, null, 20))
                .thenThrow(new com.myxhs.home.exception.DownstreamUnavailableException("内容服务不可用"));

        org.springframework.test.web.servlet.MvcResult mvcResult = mockMvc.perform(get("/api/home/feed").header("X-User-Id", 1001L))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("内容服务不可用"));
    }
}
