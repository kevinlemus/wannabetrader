package com.wannabetrader;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;

/** Checks that the backend starts. Uses a throwaway in-memory database, not your real one. */
@SpringBootTest
@AutoConfigureTestDatabase
class WannabeTraderApplicationTests {

    @Test
    void contextLoads() {
    }
}
