package com.cbc_terminal_ballistics.hitcam;

/**
 * One recorded point of a projectile's flight, sampled once per server tick.
 * A shell's real path between ticks is straight (CBC integrates and raycasts
 * per tick), so linear interpolation between samples reproduces the exact
 * trajectory at any sub-tick time.
 *
 * @param ox oy oz normalized orientation vector of the shell
 */
public record HitcamSample(double x, double y, double z, float ox, float oy, float oz) {
}
