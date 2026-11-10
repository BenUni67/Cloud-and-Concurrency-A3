package comp3011.subtitleserver.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import comp3011.subtitleserver.dto.GlobalStatsResponse;

class StatsServiceTest {

    @Test
    void concurrentTokenUpdatesDoNotLoseValues() throws Exception {

        StatsService statsService = new StatsService();
        int numberOfUpdates = 10000;
        // Holds every thread until it's ready to release
        CountDownLatch start = new CountDownLatch(1);
        
        // A pool that creates a virtual thread for each task
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        	
        	// Tracks each submitted task so we can wait
            List<Future<?>> futures = new ArrayList<>();
            
            // Submit 10k tasks, each adding teh same token stats
            for (int i = 0; i < numberOfUpdates; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    statsService.addTokenStats(1, 2);
                    return null;
                }));
            }

            // Makes all the threads start updating at basically the same time
            start.countDown();
            // Wait for every task to finish before checking result
            for (Future<?> future : futures) {
                future.get();
            }
        }
        
        GlobalStatsResponse result = statsService.getStats();
        assertEquals(10000,  result.inputTokens());
        assertEquals(20000,result.outputTokens());
    }
}