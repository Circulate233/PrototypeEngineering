package com.circulation.prototype_eng.client.gui;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuiNumbersTest {
    @Test
    void groupedAndCompactKeepTheirUsFormattingAcrossConcurrentTooltipAndGuiCalls() throws Exception {
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        var tasks = new ObjectArrayList<Callable<Void>>();
        for (int worker = 0; worker < 8; worker++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                for (int iteration = 0; iteration < 1000; iteration++) {
                    assertEquals("1,234,567", GuiNumbers.grouped(1_234_567));
                    assertEquals("9,223,372,036,854,775,807", GuiNumbers.grouped(Long.MAX_VALUE));
                    assertEquals("-9,223,372,036,854,775,808", GuiNumbers.grouped(Long.MIN_VALUE));
                    assertEquals("0", GuiNumbers.compact(-1));
                    assertEquals("999", GuiNumbers.compact(999));
                    assertEquals("1.0k", GuiNumbers.compact(1_000));
                    assertEquals("1.2k", GuiNumbers.compact(1_234));
                    assertEquals("1.0M", GuiNumbers.compact(1_000_000));
                    assertEquals("7.7M", GuiNumbers.compact(7_654_321));
                    assertEquals("1.0G", GuiNumbers.compact(1_000_000_000));
                    assertEquals("9.9G", GuiNumbers.compact(9_876_543_210L));
                }
                return null;
            });
        }
        try (var workers = Executors.newFixedThreadPool(8)) {
            var results = new ObjectArrayList<Future<Void>>();
            for (var task : tasks) results.add(workers.submit(task));
            ready.await();
            start.countDown();
            for (var result : results) result.get();
        }
    }
}
