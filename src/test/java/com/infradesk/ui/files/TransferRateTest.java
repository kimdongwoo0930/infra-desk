package com.infradesk.ui.files;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferRateTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void speedIsUnknownUntilTwoMeasurementsAreFarEnoughApart() {
        TransferRate rate = new TransferRate();

        assertEquals(0, rate.update(1, 0, 0));
        assertEquals(0, rate.update(1, 1000, SECOND / 10), "0.1초 간격은 건너뛴다");
        assertEquals(1000, rate.update(1, 2000, 2 * SECOND), 1.0, "첫 속도는 그대로");
    }

    @Test
    void laterMeasurementsAreSmoothedInsteadOfJumping() {
        TransferRate rate = new TransferRate();
        rate.update(1, 0, 0);
        rate.update(1, 1000, SECOND);          // 1000 B/s

        double speed = rate.update(1, 11_000, 2 * SECOND);   // 순간 10000 B/s

        assertTrue(speed > 1000 && speed < 10_000, "순간값을 그대로 따라가지 않는다: " + speed);
    }

    @Test
    void restartingFromZeroStartsOver() {
        TransferRate rate = new TransferRate();
        rate.update(1, 0, 0);
        rate.update(1, 5000, SECOND);

        assertEquals(0, rate.update(1, 0, 2 * SECOND), "받은 양이 줄었으면 다시 시작한 것");
    }

    @Test
    void jobsAreTrackedSeparately() {
        TransferRate rate = new TransferRate();
        rate.update(1, 0, 0);
        rate.update(2, 0, 0);

        assertEquals(100, rate.update(1, 100, SECOND), 0.001);
        assertEquals(500, rate.update(2, 500, SECOND), 0.001);
    }

    @Test
    void remainingTimeReadsInSecondsMinutesAndHours() {
        assertEquals("", TransferRate.remaining(1000, 0));
        assertEquals("2초 남음", TransferRate.remaining(2000, 1000));
        assertEquals("1초 남음", TransferRate.remaining(1, 1000), "1초 미만은 1초로 올린다");
        assertEquals("2분 남음", TransferRate.remaining(90_000, 1000));
        assertEquals("1시간 30분 남음", TransferRate.remaining(5_400_000, 1000));
    }
}
