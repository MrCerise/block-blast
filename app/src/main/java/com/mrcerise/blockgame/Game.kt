package com.mrcerise.blockgame

import kotlin.math.max
import kotlin.random.Random

/** A board cell coordinate. */
data class Cell(val row: Int, val col: Int)

/** A piece shape defined by its cells (rows go down, cols go right). */
class PieceShape(val cells: List<Cell>) {
    val rows: Int = cells.maxOf { it.row } + 1
    val cols: Int = cells.maxOf { it.col } + 1
    val size: Int = cells.size

    /** One bitmask per piece row; bit c set = the piece occupies that column. */
    val rowMasks: IntArray = IntArray(rows).also { m ->
        for (cell in cells) m[cell.row] = m[cell.row] or (1 shl cell.col)
    }
}

/** Pool of every piece shape the game can offer (Block Blast style set). */
object PiecePool {

    private fun shape(vararg rows: String): PieceShape {
        val cells = ArrayList<Cell>()
        rows.forEachIndexed { r, line ->
            line.forEachIndexed { c, ch ->
                if (ch == '#') cells.add(Cell(r, c))
            }
        }
        return PieceShape(cells)
    }

    val shapes: List<PieceShape> = listOf(
        // single
        shape("#"),
        // dominoes
        shape("##"),
        shape("#", "#"),
        // lines of 3 / 4 / 5
        shape("###"),
        shape("#", "#", "#"),
        shape("####"),
        shape("#", "#", "#", "#"),
        shape("#####"),
        shape("#", "#", "#", "#", "#"),
        // squares
        shape("##", "##"),
        shape("###", "###", "###"),
        // small corners (3 cells)
        shape("#-", "##"),
        shape("-#", "##"),
        shape("##", "#-"),
        shape("##", "-#"),
        // L / J tetrominoes (4 cells)
        shape("#-", "#-", "##"),
        shape("-#", "-#", "##"),
        shape("##", "#-", "#-"),
        shape("##", "-#", "-#"),
        shape("###", "#--"),
        shape("###", "--#"),
        shape("#--", "###"),
        shape("--#", "###"),
        // T tetrominoes (4 orientations)
        shape("###", "-#-"),
        shape("-#-", "###"),
        shape("-#", "##", "-#"),
        shape("#-", "##", "#-"),
        // S / Z tetrominoes
        shape("-##", "##-"),
        shape("##-", "-##"),
        shape("#-", "##", "-#"),
        shape("-#", "##", "#-"),
        // big corners (5 cells)
        shape("#-", "#-", "##"),
        shape("-#", "-#", "##"),
        shape("##", "-#", "-#"),
        shape("##", "#-", "#-"),
        shape("###", "#--", "#--"),
        shape("###", "--#", "--#"),
        shape("#--", "#--", "###"),
        shape("--#", "--#", "###"),
        // P / U-ish 5-cell pieces
        shape("##", "##", "#-"),
        shape("##", "##", "-#"),
        shape("#-", "##", "##"),
        shape("-#", "##", "##"),
        shape("#-#", "###")
    )

    fun random(rng: Random): PieceShape = shapes[rng.nextInt(shapes.size)]

    fun indexOf(shape: PieceShape): Int = shapes.indexOf(shape)
    fun byIndex(i: Int): PieceShape = shapes[((i % shapes.size) + shapes.size) % shapes.size]
}

/** Result of attempting to place a piece. */
class PlaceResult(
    val placed: Boolean,
    val clearedCells: List<Cell> = emptyList(),
    val clearedLines: Int = 0,
    val gained: Int = 0,
    val streak: Int = 0,
    val comboBonus: Int = 0
)

/**
 * Pure game state: 8x8 board, the 3 offered pieces, score and streak.
 * No Android dependencies so it can be unit tested on the JVM.
 */
class Game(val rng: Random = Random.Default) {

    val boardSize = 8
    val grid = Array(boardSize) { BooleanArray(boardSize) }

    var score = 0
        internal set
    var best = 0
    var streak = 0
        private set

    /**
     * Non-clearing moves the current chain can still absorb before it breaks. A clear
     * refills it to [CHAIN_GRACE_MOVES], so one dry move no longer kills a combo.
     */
    var chainGrace = 0
        private set

    var over = false
        internal set

    /** The three offered pieces; null = empty slot (waiting for a refill). */
    val tray: Array<PieceShape?> = arrayOfNulls(3)

    /**
     * Bumped on every grid/tray mutation so views can cache derived state (such as
     * "does this tray piece still fit anywhere") instead of recomputing it per frame.
     */
    var version = 0
        private set

    private fun touch() { version++ }

    init {
        refillTray()
    }

    fun reset() {
        for (r in 0 until boardSize) grid[r].fill(false)
        score = 0
        streak = 0
        chainGrace = 0
        over = false
        // drop the old pieces so a restart deals a genuinely fresh tray
        for (i in tray.indices) tray[i] = null
        refillTray()
        touch()
    }

    /**
     * How likely a refill is "rescued" into a set that is guaranteed playable. Full help
     * at the start, fading as the score climbs so the late game can genuinely end.
     * score 0 -> 100%, 2000 -> 50%, 10000 -> ~10%, never below [MIN_HELP_CHANCE].
     */
    fun helpChance(): Float {
        if (score <= 0) return 1f
        val x = Math.pow(score / 2000.0, HELP_FALLOFF)
        return (1.0 / (1.0 + x)).toFloat().coerceIn(MIN_HELP_CHANCE, 1f)
    }

    fun refillTray() {
        val empty = ArrayList<Int>(3)
        for (i in tray.indices) if (tray[i] == null) empty.add(i)
        if (empty.isEmpty()) {
            over = !anyMovePossible()
            touch()
            return
        }

        // Pieces already sitting in the tray count toward the guarantee.
        val kept = ArrayList<PieceShape>(3)
        for (p in tray) if (p != null) kept.add(p)

        var chosen: List<PieceShape>? = null
        if (rng.nextFloat() < helpChance()) {
            val budget = intArrayOf(SEARCH_BUDGET)
            var attempt = 0
            while (attempt < REFILL_ATTEMPTS && budget[0] > 0) {
                val pick = List(empty.size) { PiecePool.random(rng) }
                if (chosen == null) chosen = pick          // fall back to the first roll
                val candidate = ArrayList<PieceShape>(kept.size + pick.size)
                candidate.addAll(kept)
                candidate.addAll(pick)
                if (playableInOrder(candidate, budget)) {
                    chosen = pick
                    break
                }
                attempt++
            }
        }
        if (chosen == null) chosen = List(empty.size) { PiecePool.random(rng) }

        for (i in empty.indices) tray[empty[i]] = chosen[i]
        over = !anyMovePossible()
        touch()
    }

    // ---- solvability search ----------------------------------------------
    // Answers "can this whole tray be placed, in some order, on this board?" -- line
    // clears included, since clearing frees space for the pieces that follow.

    /** The board as one bitmask per row; bit c set = filled. */
    private fun boardMasks(): IntArray {
        val m = IntArray(boardSize)
        for (r in 0 until boardSize) {
            var v = 0
            for (c in 0 until boardSize) if (grid[r][c]) v = v or (1 shl c)
            m[r] = v
        }
        return m
    }

    private fun fitsMask(board: IntArray, p: PieceShape, r: Int, c: Int): Boolean {
        for (pr in 0 until p.rows) {
            val m = p.rowMasks[pr]
            if (m != 0 && (board[r + pr] and (m shl c)) != 0) return false
        }
        return true
    }

    /** Place [p] at (r,c) and clear any completed rows/columns, mutating [board]. */
    private fun applyMask(board: IntArray, p: PieceShape, r: Int, c: Int) {
        for (pr in 0 until p.rows) {
            val m = p.rowMasks[pr]
            if (m != 0) board[r + pr] = board[r + pr] or (m shl c)
        }
        val full = (1 shl boardSize) - 1
        var rowClear = 0
        for (rr in 0 until boardSize) if (board[rr] == full) rowClear = rowClear or (1 shl rr)
        var colClear = 0
        for (cc in 0 until boardSize) {
            val bit = 1 shl cc
            var all = true
            for (rr in 0 until boardSize) if (board[rr] and bit == 0) { all = false; break }
            if (all) colClear = colClear or bit
        }
        if (rowClear == 0 && colClear == 0) return
        for (rr in 0 until boardSize) {
            val v = if (rowClear and (1 shl rr) != 0) 0 else board[rr]
            board[rr] = v and colClear.inv() and full
        }
    }

    /**
     * Depth-first search over "place any remaining piece anywhere legal", which covers
     * every ordering. Exits on the first success. [budget] bounds the work; exhausting
     * it is treated as playable so a refill can never stall the game.
     */
    private fun solve(board: IntArray, pieces: Array<PieceShape?>, budget: IntArray): Boolean {
        var remaining = false
        for (p in pieces) if (p != null) { remaining = true; break }
        if (!remaining) return true

        for (i in pieces.indices) {
            val p = pieces[i] ?: continue
            // identical shapes at this level explore identical subtrees
            var dup = false
            for (j in 0 until i) if (pieces[j] === p) { dup = true; break }
            if (dup) continue

            for (r in 0..boardSize - p.rows) {
                for (c in 0..boardSize - p.cols) {
                    if (budget[0] <= 0) return true
                    budget[0]--
                    if (!fitsMask(board, p, r, c)) continue
                    val next = board.copyOf()
                    applyMask(next, p, r, c)
                    pieces[i] = null
                    val ok = solve(next, pieces, budget)
                    pieces[i] = p
                    if (ok) return true
                }
            }
        }
        return false
    }

    /** True if every piece in [pieces] can be placed, in some order, on the board. */
    fun playableInOrder(
        pieces: List<PieceShape>,
        budget: IntArray = intArrayOf(SEARCH_BUDGET)
    ): Boolean {
        if (pieces.isEmpty()) return true
        val arr = arrayOfNulls<PieceShape>(pieces.size)
        for (i in pieces.indices) arr[i] = pieces[i]
        return solve(boardMasks(), arr, budget)
    }

    fun canPlace(shape: PieceShape, topRow: Int, leftCol: Int): Boolean {
        if (topRow < 0 || leftCol < 0 || topRow + shape.rows > boardSize || leftCol + shape.cols > boardSize)
            return false
        for (cell in shape.cells) {
            if (grid[topRow + cell.row][leftCol + cell.col]) return false
        }
        return true
    }

    fun canPlaceAnywhere(shape: PieceShape?): Boolean {
        if (shape == null) return false
        for (r in 0..boardSize - shape.rows) {
            for (c in 0..boardSize - shape.cols) {
                if (canPlace(shape, r, c)) return true
            }
        }
        return false
    }

    fun anyMovePossible(): Boolean = tray.any { canPlaceAnywhere(it) }

    /** Place a tray piece; clears full rows/columns and computes the score. */
    fun place(slot: Int, topRow: Int, leftCol: Int): PlaceResult {
        val shape = tray[slot]
        if (over || shape == null || !canPlace(shape, topRow, leftCol)) return PlaceResult(false)

        for (cell in shape.cells) grid[topRow + cell.row][leftCol + cell.col] = true
        tray[slot] = null
        touch()

        // full rows / columns
        val fullRows = (0 until boardSize).filter { r -> (0 until boardSize).all { c -> grid[r][c] } }
        val fullCols = (0 until boardSize).filter { c -> (0 until boardSize).all { r -> grid[r][c] } }

        val cleared = LinkedHashSet<Cell>()
        for (r in fullRows) for (c in 0 until boardSize) cleared.add(Cell(r, c))
        for (c in fullCols) for (r in 0 until boardSize) cleared.add(Cell(r, c))

        for (cell in cleared) grid[cell.row][cell.col] = false

        val lines = fullRows.size + fullCols.size
        var gained = shape.size
        if (lines > 0) {
            streak += 1
            chainGrace = CHAIN_GRACE_MOVES
            gained += cleared.size * 10                       // 10 pts per cleared block
            val comboBonus = when (lines) {                   // multi-line combo
                1 -> 0
                2 -> 30
                3 -> 60
                4 -> 100
                5 -> 150
                else -> 200
            }
            val streakBonus = if (streak >= 2) (streak - 1) * 20 else 0
            gained += comboBonus + streakBonus
            score += gained
            best = max(best, score)
            return PlaceResult(true, cleared.toList(), lines, gained, streak, comboBonus + streakBonus)
        } else {
            // a dry move no longer kills the chain outright -- it burns grace first
            if (streak > 0) {
                if (chainGrace > 0) chainGrace -= 1 else streak = 0
            }
            score += gained
            best = max(best, score)
            if (tray.all { it == null }) refillTray()
            over = !anyMovePossible()
            return PlaceResult(true, emptyList(), 0, gained, streak, 0)
        }
    }

    /** Called by the UI once the clear animation has finished. */
    fun afterClearAnim() {
        // like the real game, a fresh set of pieces only appears once all three are used
        if (tray.all { it == null }) refillTray()
        else over = !anyMovePossible()
        touch()
    }

    // ---- persistence -----------------------------------------------------

    fun serializeGrid(): String = buildString {
        for (r in 0 until boardSize) {
            for (c in 0 until boardSize) append(if (grid[r][c]) '1' else '0')
        }
    }

    fun deserializeGrid(s: String) {
        for (r in 0 until boardSize) grid[r].fill(false)
        s.forEachIndexed { i, ch ->
            if (ch == '1') {
                val r = i / boardSize
                val c = i % boardSize
                if (r < boardSize && c < boardSize) grid[r][c] = true
            }
        }
        touch()   // after the mutation, so cached derived state can never go stale
    }

    fun serializeTray(): String = tray.joinToString(",") { it?.let { PiecePool.indexOf(it).toString() } ?: "-1" }

    companion object {
        /** Non-clearing moves a combo chain survives before it breaks. */
        const val CHAIN_GRACE_MOVES = 2

        /** Re-rolls allowed while looking for a guaranteed-playable tray. */
        private const val REFILL_ATTEMPTS = 24

        /** Upper bound on search nodes per refill, so a crowded board can't stall. */
        private const val SEARCH_BUDGET = 40000

        private const val MIN_HELP_CHANCE = 0.08f
        private const val HELP_FALLOFF = 1.365
    }

    fun deserializeTray(s: String) {
        val parts = s.split(",")
        for (i in 0 until 3) {
            tray[i] = parts.getOrNull(i)?.toIntOrNull()?.let { if (it >= 0) PiecePool.byIndex(it) else null }
        }
        touch()
    }
}
