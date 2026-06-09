package com.cbc_terminal_ballistics.ballistics;

public enum TBCaliber {
    // the penetrate/stop decision, which follows CBC mass/momentum vs block toughness.
    // integrityDamageScale: permanent armor fatigue;
    // massLossScale: how quickly projectiles are bled off across layers; spallScale: AP spall output.
    // Autocannon AP is allowed to keep flying after penetration
    // while cannon calibers lose noticeably more energy per plate to reduce over-penetration.
    AUTOCANNON(0.5, 0.04, 1, 0.3),
    HEAVY_AUTOCANNON(1, 0.18, 1, 0.65),
    SMALL(1, 0.18, 1, 0.65),
    SMALL_MEDIUM(1, 0.25, 1, 0.85),
    MEDIUM(1, 0.3, 1, 1.05),
    BIG(1, 0.45, 1, 1.5);

    public final double penetrationScale;
    public final double integrityDamageScale;
    public final double massLossScale;
    public final double spallScale;

    TBCaliber(double penetrationScale, double integrityDamageScale, double massLossScale, double spallScale) {
        this.penetrationScale = penetrationScale;
        this.integrityDamageScale = integrityDamageScale;
        this.massLossScale = massLossScale;
        this.spallScale = spallScale;
    }
}
