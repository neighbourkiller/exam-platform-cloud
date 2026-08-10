package com.ekusys.exam.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.gateway.exam-entry-rate-limit")
public class ExamEntryRateLimitProperties {
    private boolean enabled = true;
    private Limits prepare = new Limits(500, 1_000);
    private Limits activate = new Limits(1_200, 2_400);
    private Limits paperDelivery = new Limits(1_200, 2_400);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Limits getPrepare() {
        return prepare;
    }

    public void setPrepare(Limits prepare) {
        this.prepare = prepare;
    }

    public Limits getActivate() {
        return activate;
    }

    public void setActivate(Limits activate) {
        this.activate = activate;
    }

    public Limits getPaperDelivery() {
        return paperDelivery;
    }

    public void setPaperDelivery(Limits paperDelivery) {
        this.paperDelivery = paperDelivery;
    }

    public Limits forEndpoint(String endpoint) {
        return switch (endpoint) {
            case "prepare" -> prepare;
            case "activate" -> activate;
            case "paper-delivery" -> paperDelivery;
            default -> throw new IllegalArgumentException("Unsupported exam entry endpoint: " + endpoint);
        };
    }

    public static class Limits {
        private int replenishRate;
        private int burstCapacity;

        public Limits() {
        }

        public Limits(int replenishRate, int burstCapacity) {
            this.replenishRate = replenishRate;
            this.burstCapacity = burstCapacity;
        }

        public int getReplenishRate() {
            return replenishRate;
        }

        public void setReplenishRate(int replenishRate) {
            this.replenishRate = replenishRate;
        }

        public int getBurstCapacity() {
            return burstCapacity;
        }

        public void setBurstCapacity(int burstCapacity) {
            this.burstCapacity = burstCapacity;
        }

        public int safeReplenishRate() {
            return Math.max(1, replenishRate);
        }

        public int safeBurstCapacity() {
            return Math.max(safeReplenishRate(), burstCapacity);
        }
    }
}
