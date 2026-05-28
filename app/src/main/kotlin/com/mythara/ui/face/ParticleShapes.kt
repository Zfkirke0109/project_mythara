package com.mythara.ui.face

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Catalogue of 3D shapes the [FaceMesh] particles can assemble into.
 * Each invocation of [sampleShape] returns flat per-coordinate arrays
 * `(xs, ys, zs)` of length [n], normalised so points fit roughly inside
 * a ball of radius [radius] centred at the origin.
 *
 * Polytopes (Tetrahedron, Cube, Octahedron, Icosahedron) sample along
 * EDGES — wireframe-style — so the topology is visible with the
 * particle counts we have (~430). A small Gaussian jitter on the
 * cross-section gives the lines visible thickness without smudging the
 * silhouette.
 *
 * Parametric shapes (Torus, TrefoilKnot) sample the tube around their
 * curve; particles distribute uniformly on the surface so the shape
 * reads as solid even when the camera angle hides the wireframe.
 *
 * Per-frame rotation is done outside this file — see
 * [rodriguesRotateInPlace] for the axis-angle math that keeps the
 * assembled shape spinning around its session-randomised axis.
 */
object ParticleShapes {

    /** The shape catalogue. A random pick is minted at every
     *  face-detect session in [FaceMesh] so the user sees a different
     *  shape each time they pick the phone up to look at it. */
    enum class Kind { Tetrahedron, Cube, Octahedron, Icosahedron, Torus, TrefoilKnot }

    /** Sample [n] points along the chosen shape and write them into
     *  the provided flat arrays. Allocating the arrays once and reusing
     *  them across sessions avoids per-session GC churn. */
    fun sampleShape(
        kind: Kind,
        n: Int,
        radius: Float,
        rnd: Random,
        xs: FloatArray,
        ys: FloatArray,
        zs: FloatArray,
    ) {
        require(xs.size >= n && ys.size >= n && zs.size >= n) { "buffers too small for $n samples" }
        when (kind) {
            Kind.Tetrahedron -> sampleEdges(TETRA_VERTS, TETRA_EDGES, radius, n, rnd, xs, ys, zs)
            Kind.Cube -> sampleEdges(CUBE_VERTS, CUBE_EDGES, radius * 0.85f, n, rnd, xs, ys, zs)
            Kind.Octahedron -> sampleEdges(OCTA_VERTS, OCTA_EDGES, radius, n, rnd, xs, ys, zs)
            Kind.Icosahedron -> sampleEdges(ICOSA_VERTS, ICOSA_EDGES, radius, n, rnd, xs, ys, zs)
            Kind.Torus -> sampleTorus(radius, n, rnd, xs, ys, zs)
            Kind.TrefoilKnot -> sampleTrefoilKnot(radius, n, rnd, xs, ys, zs)
        }
    }

    // ─── Polytope wireframe sampler ────────────────────────────────

    private fun sampleEdges(
        verts: Array<FloatArray>,
        edges: IntArray, // flat (a0, b0, a1, b1, ...) pairs
        radius: Float,
        n: Int,
        rnd: Random,
        xs: FloatArray,
        ys: FloatArray,
        zs: FloatArray,
    ) {
        val edgeCount = edges.size / 2
        val jitterSigma = radius * 0.012f
        for (i in 0 until n) {
            val e = rnd.nextInt(edgeCount)
            val a = edges[e * 2]
            val b = edges[e * 2 + 1]
            val t = rnd.nextFloat()
            val omt = 1f - t
            val px = verts[a][0] * omt + verts[b][0] * t
            val py = verts[a][1] * omt + verts[b][1] * t
            val pz = verts[a][2] * omt + verts[b][2] * t
            // Small radial jitter so lines have visual thickness.
            xs[i] = px * radius + gauss(rnd) * jitterSigma
            ys[i] = py * radius + gauss(rnd) * jitterSigma
            zs[i] = pz * radius + gauss(rnd) * jitterSigma
        }
    }

    // ─── Torus tube sampler ────────────────────────────────────────

    /** Standard torus parametrisation. `theta` walks the major loop;
     *  `phi` walks the minor tube cross-section. Major radius ≈ 0.7R
     *  + minor 0.25R fills the brand circle reliably without
     *  clipping at any rotation. */
    private fun sampleTorus(
        radius: Float,
        n: Int,
        rnd: Random,
        xs: FloatArray,
        ys: FloatArray,
        zs: FloatArray,
    ) {
        val majorR = radius * 0.70f
        val minorR = radius * 0.25f
        for (i in 0 until n) {
            val theta = rnd.nextFloat() * 2f * PI.toFloat()
            val phi = rnd.nextFloat() * 2f * PI.toFloat()
            val cosT = cos(theta); val sinT = sin(theta)
            val cosP = cos(phi); val sinP = sin(phi)
            xs[i] = (majorR + minorR * cosP) * cosT
            ys[i] = minorR * sinP
            zs[i] = (majorR + minorR * cosP) * sinT
        }
    }

    // ─── Trefoil knot tube sampler ─────────────────────────────────

    /** Trefoil knot. The natural curve has x ≈ y ≈ [-3, 3]; scale it
     *  to fit the brand radius. A small constant-radius tube around
     *  the curve fills out the surface so points distribute across
     *  the visible shape, not just the centreline. */
    private fun sampleTrefoilKnot(
        radius: Float,
        n: Int,
        rnd: Random,
        xs: FloatArray,
        ys: FloatArray,
        zs: FloatArray,
    ) {
        val scale = radius / 3.2f
        val tubeR = radius * 0.08f
        for (i in 0 until n) {
            val t = rnd.nextFloat() * 2f * PI.toFloat()
            val phi = rnd.nextFloat() * 2f * PI.toFloat()
            val cx = (sin(t) + 2f * sin(2f * t)) * scale
            val cy = (cos(t) - 2f * cos(2f * t)) * scale
            val cz = -sin(3f * t) * scale
            // Crude tube perturbation in the xy plane — good enough
            // for a visual approximation. A proper Frenet frame
            // wouldn't move the particles meaningfully more for this
            // size + count.
            xs[i] = cx + cos(phi) * tubeR
            ys[i] = cy + sin(phi) * tubeR
            zs[i] = cz
        }
    }

    // ─── Random unit vector (rotation axis) ────────────────────────

    /** Uniform point on the unit sphere via the cylinder-mapping
     *  trick (Marsaglia 1972 alternative — same result, fewer
     *  trig calls). Used as the per-session spin axis. */
    fun randomUnitVector(rnd: Random, out: FloatArray) {
        val u = rnd.nextFloat() * 2f - 1f
        val theta = rnd.nextFloat() * 2f * PI.toFloat()
        val r = sqrt(1f - u * u)
        out[0] = r * cos(theta)
        out[1] = r * sin(theta)
        out[2] = u
    }

    // ─── Polytope topology data (unit-sphere vertices) ─────────────

    private val TETRA_VERTS: Array<FloatArray> = arrayOf(
        floatArrayOf(1f, 1f, 1f),
        floatArrayOf(1f, -1f, -1f),
        floatArrayOf(-1f, 1f, -1f),
        floatArrayOf(-1f, -1f, 1f),
    ).also { normalise(it) }
    private val TETRA_EDGES = intArrayOf(
        0, 1, 0, 2, 0, 3, 1, 2, 1, 3, 2, 3,
    )

    private val CUBE_VERTS: Array<FloatArray> = (0 until 8).map { i ->
        floatArrayOf(
            if (i and 1 == 0) -1f else 1f,
            if (i and 2 == 0) -1f else 1f,
            if (i and 4 == 0) -1f else 1f,
        )
    }.toTypedArray()
    private val CUBE_EDGES = intArrayOf(
        0, 1, 0, 2, 0, 4, 1, 3, 1, 5, 2, 3,
        2, 6, 3, 7, 4, 5, 4, 6, 5, 7, 6, 7,
    )

    private val OCTA_VERTS: Array<FloatArray> = arrayOf(
        floatArrayOf(1f, 0f, 0f),
        floatArrayOf(-1f, 0f, 0f),
        floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, -1f, 0f),
        floatArrayOf(0f, 0f, 1f),
        floatArrayOf(0f, 0f, -1f),
    )
    private val OCTA_EDGES = intArrayOf(
        0, 2, 0, 3, 0, 4, 0, 5,
        1, 2, 1, 3, 1, 4, 1, 5,
        2, 4, 2, 5, 3, 4, 3, 5,
    )

    /** Icosahedron via golden-ratio rectangles. The 12 vertices live on
     *  three orthogonal rectangles of side 2 × 2φ; normalising to the
     *  unit sphere gives the canonical icosahedron and lets us pick
     *  edges by squared-distance threshold without hand-listing all 30. */
    private val ICOSA_VERTS: Array<FloatArray> = run {
        val phi = (1f + sqrt(5f)) / 2f
        val raw = arrayOf(
            floatArrayOf(0f, 1f, phi), floatArrayOf(0f, 1f, -phi),
            floatArrayOf(0f, -1f, phi), floatArrayOf(0f, -1f, -phi),
            floatArrayOf(1f, phi, 0f), floatArrayOf(1f, -phi, 0f),
            floatArrayOf(-1f, phi, 0f), floatArrayOf(-1f, -phi, 0f),
            floatArrayOf(phi, 0f, 1f), floatArrayOf(phi, 0f, -1f),
            floatArrayOf(-phi, 0f, 1f), floatArrayOf(-phi, 0f, -1f),
        )
        normalise(raw)
        raw
    }
    private val ICOSA_EDGES: IntArray = run {
        // An icosahedron's edges all have the same length. With the
        // vertices normalised onto the unit sphere, that length squared
        // is (2 - 2/√5) ≈ 1.106; tolerance 1.3 catches it cleanly
        // without admitting a diagonal (next-shortest squared ≈ 3.6).
        val out = ArrayList<Int>(60)
        for (i in 0 until ICOSA_VERTS.size) {
            for (j in i + 1 until ICOSA_VERTS.size) {
                val dx = ICOSA_VERTS[i][0] - ICOSA_VERTS[j][0]
                val dy = ICOSA_VERTS[i][1] - ICOSA_VERTS[j][1]
                val dz = ICOSA_VERTS[i][2] - ICOSA_VERTS[j][2]
                if (dx * dx + dy * dy + dz * dz < 1.3f) {
                    out.add(i); out.add(j)
                }
            }
        }
        out.toIntArray()
    }

    private fun normalise(verts: Array<FloatArray>) {
        for (v in verts) {
            val r = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (r > 0f) {
                v[0] /= r; v[1] /= r; v[2] /= r
            }
        }
    }

    private fun gauss(rnd: Random): Float {
        val u1 = rnd.nextDouble().coerceAtLeast(1e-9).toFloat()
        val u2 = rnd.nextFloat()
        return sqrt(-2f * ln(u1)) * cos(2f * PI.toFloat() * u2)
    }
}

/**
 * Rotate a 3D point [px, py, pz] around the unit axis (kx, ky, kz) by
 * the angle whose (cos, sin) are (c, s). Returns the rotated coords in
 * [out] (length ≥ 3).
 *
 * Rodrigues' rotation formula:
 *   p' = p·cos + (k × p)·sin + k·(k·p)·(1 − cos)
 *
 * Inlined to avoid Vec3 allocations on the hot per-particle path —
 * 430 particles × 60 fps would create 25k garbage objects per second
 * otherwise.
 */
fun rodriguesRotate(
    px: Float, py: Float, pz: Float,
    kx: Float, ky: Float, kz: Float,
    c: Float, s: Float,
    out: FloatArray,
) {
    val dot = kx * px + ky * py + kz * pz
    val crossX = ky * pz - kz * py
    val crossY = kz * px - kx * pz
    val crossZ = kx * py - ky * px
    val oneMinusC = 1f - c
    out[0] = px * c + crossX * s + kx * dot * oneMinusC
    out[1] = py * c + crossY * s + ky * dot * oneMinusC
    out[2] = pz * c + crossZ * s + kz * dot * oneMinusC
}
