package com.yiyan.go.game;

/** Application difficulty bands, not certified human ranks. */
public enum GoDifficulty {
    ONE("一段", 16, 0.25, 0.80),
    TWO("二段", 32, 0.40, 0.65),
    THREE("三段", 64, 0.60, 0.50),
    FOUR("四段", 128, 1.0, 0.38),
    FIVE("五段", 256, 1.5, 0.28),
    SIX("六段", 512, 2.5, 0.20),
    SEVEN("七段", 1024, 4.0, 0.12),
    EIGHT("八段", 2048, 6.0, 0.06),
    NINE("九段", 4096, 10.0, 0.0);

    private final String label;
    private final int visits;
    private final double seconds;
    private final double temperature;

    GoDifficulty(String label, int visits, double seconds, double temperature) {
        this.label = label;
        this.visits = visits;
        this.seconds = seconds;
        this.temperature = temperature;
    }

    public int visits() { return visits; }
    public double seconds() { return seconds; }
    public double temperature() { return temperature; }
    public String opponentName() { return "KataGo · " + label; }
    @Override public String toString() { return label; }
}
