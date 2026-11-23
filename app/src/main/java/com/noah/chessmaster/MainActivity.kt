package com.noah.chessmaster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// ============================================================================
// 1. DOMAIN & CHESS ENGINE (INKL. HARDCORE-MODUS & SPEZIALREGELN)
// ============================================================================

enum class PieceColor {
    WHITE, BLACK;

    fun opposite() = if (this == WHITE) BLACK else WHITE
}

enum class PieceType(val value: Int, val symbolW: String, val symbolB: String, val deName: String) {
    KING(20000, "♔", "♚", "König"),
    QUEEN(900, "♕", "♛", "Dame"),
    ROOK(500, "♖", "♜", "Turm"),
    BISHOP(330, "♗", "♝", "Läufer"),
    KNIGHT(320, "♘", "♞", "Springer"),
    PAWN(100, "♙", "♟", "Bauer")
}

data class Piece(val type: PieceType, val color: PieceColor, val hasMoved: Boolean = false)

data class Pos(val r: Int, val c: Int) {
    fun isValid() = r in 0..7 && c in 0..7
    fun toAlg(): String = "${'a' + c}${8 - r}"
}

enum class SpecialMoveType { NONE, EN_PASSANT, CASTLE_KINGSIDE, CASTLE_QUEENSIDE, PROMOTION }

data class Move(
    val from: Pos,
    val to: Pos,
    val piece: Piece,
    val captured: Piece? = null,
    val special: SpecialMoveType = SpecialMoveType.NONE,
    val promoteTo: PieceType? = null,
    val isPseudoLegalOnly: Boolean = false,
    val illegalReason: String = ""
) {
    fun toNotation(): String {
        if (special == SpecialMoveType.CASTLE_KINGSIDE) return "O-O"
        if (special == SpecialMoveType.CASTLE_QUEENSIDE) return "O-O-O"
        val pPrefix = when (piece.type) {
            PieceType.KING -> "K"
            PieceType.QUEEN -> "D"
            PieceType.ROOK -> "T"
            PieceType.BISHOP -> "L"
            PieceType.KNIGHT -> "S"
            PieceType.PAWN -> if (captured != null) "${'a' + from.c}" else ""
        }
        val cap = if (captured != null) "x" else ""
        val prom = if (promoteTo != null) "=${promoteTo.name.first()}" else ""
        val ep = if (special == SpecialMoveType.EN_PASSANT) " e.p." else ""
        return "$pPrefix$cap${to.toAlg()}$prom$ep"
    }
}

data class BoardState(
    val grid: List<List<Piece?>>,
    val turn: PieceColor = PieceColor.WHITE,
    val enPassantTarget: Pos? = null,
    val halfMoveClock: Int = 0
) {
    fun pieceAt(r: Int, c: Int): Piece? = if (r in 0..7 && c in 0..7) grid[r][c] else null
    fun pieceAt(p: Pos): Piece? = pieceAt(p.r, p.c)

    fun findKing(color: PieceColor): Pos? {
        for (r in 0..7) for (c in 0..7) {
            val p = grid[r][c]
            if (p != null && p.color == color && p.type == PieceType.KING) return Pos(r, c)
        }
        return null
    }

    fun applyMove(move: Move): BoardState {
        val mutable = grid.map { it.toMutableList() }.toMutableList()
        val movingPiece = move.piece.copy(hasMoved = true)
        mutable[move.from.r][move.from.c] = null

        if (move.special == SpecialMoveType.EN_PASSANT) {
            mutable[move.from.r][move.to.c] = null
        } else if (move.special == SpecialMoveType.CASTLE_KINGSIDE) {
            val rook = mutable[move.from.r][7]
            mutable[move.from.r][7] = null
            mutable[move.from.r][5] = rook?.copy(hasMoved = true)
        } else if (move.special == SpecialMoveType.CASTLE_QUEENSIDE) {
            val rook = mutable[move.from.r][0]
            mutable[move.from.r][0] = null
            mutable[move.from.r][3] = rook?.copy(hasMoved = true)
        }

        val finalPiece = if (move.special == SpecialMoveType.PROMOTION && move.promoteTo != null) {
            Piece(move.promoteTo, move.piece.color, true)
        } else movingPiece

        mutable[move.to.r][move.to.c] = finalPiece

        val nextEp = if (move.piece.type == PieceType.PAWN && abs(move.to.r - move.from.r) == 2) {
            Pos((move.from.r + move.to.r) / 2, move.from.c)
        } else null

        return BoardState(
            grid = mutable.map { it.toList() },
            turn = turn.opposite(),
            enPassantTarget = nextEp,
            halfMoveClock = if (move.piece.type == PieceType.PAWN || move.captured != null) 0 else halfMoveClock + 1
        )
    }

    companion object {
        fun initial(): BoardState {
            val emptyRow = List<Piece?>(8) { null }
            fun backRow(color: PieceColor) = listOf(
                Piece(PieceType.ROOK, color),
                Piece(PieceType.KNIGHT, color),
                Piece(PieceType.BISHOP, color),
                Piece(PieceType.QUEEN, color),
                Piece(PieceType.KING, color),
                Piece(PieceType.BISHOP, color),
                Piece(PieceType.KNIGHT, color),
                Piece(PieceType.ROOK, color)
            )
            fun pawnRow(color: PieceColor) = List(8) { Piece(PieceType.PAWN, color) }
            return BoardState(
                grid = listOf(
                    backRow(PieceColor.BLACK),
                    pawnRow(PieceColor.BLACK),
                    emptyRow, emptyRow, emptyRow, emptyRow,
                    pawnRow(PieceColor.WHITE),
                    backRow(PieceColor.WHITE)
                )
            )
        }
    }
}

object ChessRules {
    fun isSquareAttacked(state: BoardState, sq: Pos, byColor: PieceColor): Boolean {
        val pawnDir = if (byColor == PieceColor.WHITE) -1 else 1
        for (dc in listOf(-1, 1)) {
            val pr = sq.r - pawnDir
            val pc = sq.c + dc
            val p = state.pieceAt(pr, pc)
            if (p != null && p.color == byColor && p.type == PieceType.PAWN) return true
        }
        val knightD = listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
        for ((dr, dc) in knightD) {
            val p = state.pieceAt(sq.r + dr, sq.c + dc)
            if (p != null && p.color == byColor && p.type == PieceType.KNIGHT) return true
        }
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val p = state.pieceAt(sq.r + dr, sq.c + dc)
            if (p != null && p.color == byColor && p.type == PieceType.KING) return true
        }
        val ortho = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        for ((dr, dc) in ortho) {
            var r = sq.r + dr
            var c = sq.c + dc
            while (r in 0..7 && c in 0..7) {
                val p = state.pieceAt(r, c)
                if (p != null) {
                    if (p.color == byColor && (p.type == PieceType.ROOK || p.type == PieceType.QUEEN)) return true
                    break
                }
                r += dr
                c += dc
            }
        }
        val diag = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
        for ((dr, dc) in diag) {
            var r = sq.r + dr
            var c = sq.c + dc
            while (r in 0..7 && c in 0..7) {
                val p = state.pieceAt(r, c)
                if (p != null) {
                    if (p.color == byColor && (p.type == PieceType.BISHOP || p.type == PieceType.QUEEN)) return true
                    break
                }
                r += dr
                c += dc
            }
        }
        return false
    }

    fun isInCheck(state: BoardState, color: PieceColor): Boolean {
        val kPos = state.findKing(color) ?: return true
        return isSquareAttacked(state, kPos, color.opposite())
    }

    fun generateMoves(state: BoardState, color: PieceColor, hardcoreMode: Boolean): List<Move> {
        val rawMoves = mutableListOf<Move>()
        for (r in 0..7) for (c in 0..7) {
            val p = state.pieceAt(r, c) ?: continue
            if (p.color != color) continue
            val from = Pos(r, c)
            when (p.type) {
                PieceType.PAWN -> {
                    val dir = if (color == PieceColor.WHITE) -1 else 1
                    val startRow = if (color == PieceColor.WHITE) 6 else 1
                    val promoRow = if (color == PieceColor.WHITE) 0 else 7
                    val oneStep = Pos(r + dir, c)
                    if (oneStep.isValid() && state.pieceAt(oneStep) == null) {
                        if (oneStep.r == promoRow) {
                            listOf(PieceType.QUEEN, PieceType.KNIGHT, PieceType.ROOK, PieceType.BISHOP).forEach {
                                rawMoves.add(Move(from, oneStep, p, null, SpecialMoveType.PROMOTION, it))
                            }
                        } else {
                            rawMoves.add(Move(from, oneStep, p))
                            val twoStep = Pos(r + 2 * dir, c)
                            if (r == startRow && state.pieceAt(twoStep) == null) {
                                rawMoves.add(Move(from, twoStep, p))
                            }
                        }
                    }
                    for (dc in listOf(-1, 1)) {
                        val capPos = Pos(r + dir, c + dc)
                        if (!capPos.isValid()) continue
                        val target = state.pieceAt(capPos)
                        if (target != null && target.color != color) {
                            if (capPos.r == promoRow) {
                                listOf(PieceType.QUEEN, PieceType.KNIGHT, PieceType.ROOK, PieceType.BISHOP).forEach {
                                    rawMoves.add(Move(from, capPos, p, target, SpecialMoveType.PROMOTION, it))
                                }
                            } else {
                                rawMoves.add(Move(from, capPos, p, target))
                            }
                        } else if (state.enPassantTarget == capPos) {
                            val epPawn = state.pieceAt(r, c + dc)
                            if (epPawn != null && epPawn.color != color && epPawn.type == PieceType.PAWN) {
                                rawMoves.add(Move(from, capPos, p, epPawn, SpecialMoveType.EN_PASSANT))
                            }
                        }
                    }
                }
                PieceType.KNIGHT -> {
                    listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1).forEach { (dr, dc) ->
                        val to = Pos(r + dr, c + dc)
                        if (to.isValid()) {
                            val t = state.pieceAt(to)
                            if (t == null || t.color != color) rawMoves.add(Move(from, to, p, t))
                        }
                    }
                }
                PieceType.BISHOP -> addSlidingMoves(state, from, p, listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1), rawMoves)
                PieceType.ROOK -> addSlidingMoves(state, from, p, listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1), rawMoves)
                PieceType.QUEEN -> addSlidingMoves(state, from, p, listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1, -1 to 0, 1 to 0, 0 to -1, 0 to 1), rawMoves)
                PieceType.KING -> {
                    for (dr in -1..1) for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        val to = Pos(r + dr, c + dc)
                        if (to.isValid()) {
                            val t = state.pieceAt(to)
                            if (t == null || t.color != color) rawMoves.add(Move(from, to, p, t))
                        }
                    }
                    val homeRow = if (color == PieceColor.WHITE) 7 else 0
                    if (!p.hasMoved && r == homeRow && c == 4) {
                        val kRook = state.pieceAt(homeRow, 7)
                        if (kRook != null && kRook.type == PieceType.ROOK && !kRook.hasMoved &&
                            state.pieceAt(homeRow, 5) == null && state.pieceAt(homeRow, 6) == null
                        ) {
                            val castleIllegal = isInCheck(state, color) ||
                                    isSquareAttacked(state, Pos(homeRow, 5), color.opposite()) ||
                                    isSquareAttacked(state, Pos(homeRow, 6), color.opposite())
                            if (!castleIllegal || hardcoreMode) {
                                rawMoves.add(
                                    Move(
                                        from, Pos(homeRow, 6), p, null, SpecialMoveType.CASTLE_KINGSIDE,
                                        isPseudoLegalOnly = castleIllegal,
                                        illegalReason = if (castleIllegal) "Rochade illegal: König stand im Schach oder zog über ein bedrohtes Feld!" else ""
                                    )
                                )
                            }
                        }
                        val qRook = state.pieceAt(homeRow, 0)
                        if (qRook != null && qRook.type == PieceType.ROOK && !qRook.hasMoved &&
                            state.pieceAt(homeRow, 1) == null && state.pieceAt(homeRow, 2) == null && state.pieceAt(homeRow, 3) == null
                        ) {
                            val castleIllegal = isInCheck(state, color) ||
                                    isSquareAttacked(state, Pos(homeRow, 3), color.opposite()) ||
                                    isSquareAttacked(state, Pos(homeRow, 2), color.opposite())
                            if (!castleIllegal || hardcoreMode) {
                                rawMoves.add(
                                    Move(
                                        from, Pos(homeRow, 2), p, null, SpecialMoveType.CASTLE_QUEENSIDE,
                                        isPseudoLegalOnly = castleIllegal,
                                        illegalReason = if (castleIllegal) "Lange Rochade illegal: König stand im Schach oder zog über ein bedrohtes Feld!" else ""
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        val result = mutableListOf<Move>()
        for (m in rawMoves) {
            val next = state.applyMove(m)
            val leavesKingInCheck = isInCheck(next, color)
            if (leavesKingInCheck || m.isPseudoLegalOnly) {
                if (hardcoreMode) {
                    val reason = m.illegalReason.ifEmpty {
                        when {
                            m.piece.type == PieceType.KING -> "Du bist mit deinem König direkt in ein angegriffenes Feld gelaufen!"
                            m.special == SpecialMoveType.EN_PASSANT -> "En-Passant-Falle! Durch das Entfernen beider Bauern wurde die Linie auf deinen König geöffnet."
                            else -> "Fesselung übersehen! Deine Figur (${m.piece.type.deName}) hat deinen König gedeckt – jetzt ist er ungeschützt!"
                        }
                    }
                    result.add(m.copy(isPseudoLegalOnly = true, illegalReason = reason))
                }
            } else {
                result.add(m)
            }
        }
        return result
    }

    private fun addSlidingMoves(state: BoardState, from: Pos, p: Piece, dirs: List<Pair<Int, Int>>, out: MutableList<Move>) {
        for ((dr, dc) in dirs) {
            var r = from.r + dr
            var c = from.c + dc
            while (r in 0..7 && c in 0..7) {
                val t = state.pieceAt(r, c)
                if (t == null) {
                    out.add(Move(from, Pos(r, c), p))
                } else {
                    if (t.color != p.color) out.add(Move(from, Pos(r, c), p, t))
                    break
                }
                r += dr
                c += dc
            }
        }
    }
}

// ============================================================================
// 2. KI-ENGINE, LIVE-COACH & CHESS.COM-STYLE POST-GAME ANALYSE
// ============================================================================

enum class MoveBadge(val tag: String, val label: String, val color: Color) {
    BRILLIANT("!!", "Brillant", Color(0xFF1BACA6)),
    GREAT("!", "Einziger Zug", Color(0xFF5C8BB0)),
    BEST("✓", "Bester Zug", Color(0xFF81B64C)),
    GOOD("•", "Gut", Color(0xFF96BC4B)),
    INACCURACY("?!", "Ungenau", Color(0xFFF7C631)),
    MISTAKE("?", "Fehler", Color(0xFFE6912C)),
    BLUNDER("??", "Blunder", Color(0xFFCA3431)),
    ILLEGAL_SUICIDE("💀", "Regel-Selbstmord", Color(0xFFFF1744))
}

data class AnalyzedMove(
    val moveNumber: Int,
    val move: Move,
    val boardBefore: BoardState,
    val boardAfter: BoardState,
    val evalBefore: Int,
    val evalAfter: Int,
    val bestMove: Move?,
    val badge: MoveBadge,
    val explanation: String
)

object ChessAIAndCoach {
    private val centerBonus = arrayOf(
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(5, 10, 10, -20, -20, 10, 10, 5),
        intArrayOf(5, -5, -10, 0, 0, -10, -5, 5),
        intArrayOf(0, 0, 0, 25, 25, 0, 0, 0),
        intArrayOf(5, 5, 10, 30, 30, 10, 5, 5),
        intArrayOf(10, 10, 20, 30, 30, 20, 10, 10),
        intArrayOf(50, 50, 50, 50, 50, 50, 50, 50),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
    )

    fun evaluate(state: BoardState): Int {
        if (state.findKing(PieceColor.WHITE) == null) return -99999
        if (state.findKing(PieceColor.BLACK) == null) return 99999
        var score = 0
        for (r in 0..7) for (c in 0..7) {
            val p = state.pieceAt(r, c) ?: continue
            val posR = if (p.color == PieceColor.WHITE) 7 - r else r
            val total = p.type.value + centerBonus[posR][c]
            score += if (p.color == PieceColor.WHITE) total else -total
        }
        return score
    }

    fun findBestMove(state: BoardState, depth: Int = 2): Pair<Move?, Int> {
        val isMax = state.turn == PieceColor.WHITE
        val moves = ChessRules.generateMoves(state, state.turn, hardcoreMode = false)
        if (moves.isEmpty()) {
            return null to if (ChessRules.isInCheck(state, state.turn)) (if (isMax) -90000 else 90000) else 0
        }
        var bestMove: Move? = moves.first()
        var bestEval = if (isMax) Int.MIN_VALUE else Int.MAX_VALUE

        val sorted = moves.sortedByDescending { (it.captured?.type?.value ?: 0) }
        for (m in sorted) {
            val next = state.applyMove(m)
            val eval = minimax(next, depth - 1, Int.MIN_VALUE, Int.MAX_VALUE, !isMax)
            if (isMax && eval > bestEval) {
                bestEval = eval
                bestMove = m
            } else if (!isMax && eval < bestEval) {
                bestEval = eval
                bestMove = m
            }
        }
        return bestMove to bestEval
    }

    private fun minimax(state: BoardState, depth: Int, alphaIn: Int, betaIn: Int, maximizing: Boolean): Int {
        if (depth == 0) return evaluate(state)
        val moves = ChessRules.generateMoves(state, state.turn, hardcoreMode = false)
        if (moves.isEmpty()) {
            return if (ChessRules.isInCheck(state, state.turn)) (if (maximizing) -90000 - depth else 90000 + depth) else 0
        }
        var alpha = alphaIn
        var beta = betaIn
        val sorted = moves.sortedByDescending { (it.captured?.type?.value ?: 0) }
        if (maximizing) {
            var maxEval = Int.MIN_VALUE
            for (m in sorted) {
                val ev = minimax(state.applyMove(m), depth - 1, alpha, beta, false)
                maxEval = max(maxEval, ev)
                alpha = max(alpha, ev)
                if (beta <= alpha) break
            }
            return maxEval
        } else {
            var minEval = Int.MAX_VALUE
            for (m in sorted) {
                val ev = minimax(state.applyMove(m), depth - 1, alpha, beta, true)
                minEval = kotlin.math.min(minEval, ev)
                beta = kotlin.math.min(beta, ev)
                if (beta <= alpha) break
            }
            return minEval
        }
    }

    fun getCoachSuggestions(state: BoardState): List<Triple<Move, Int, String>> {
        val moves = ChessRules.generateMoves(state, state.turn, hardcoreMode = false)
        val isMax = state.turn == PieceColor.WHITE
        val scored = moves.map { m ->
            val ev = minimax(state.applyMove(m), 1, Int.MIN_VALUE, Int.MAX_VALUE, !isMax)
            m to ev
        }.sortedBy { if (isMax) -it.second else it.second }.take(3)

        return scored.map { (m, ev) ->
            val tip = when {
                m.special == SpecialMoveType.EN_PASSANT -> "Spezialzug En Passant! Schlägt den Bauern im Vorbeigehen."
                m.special == SpecialMoveType.CASTLE_KINGSIDE || m.special == SpecialMoveType.CASTLE_QUEENSIDE -> "Rochade! Bringt deinen König in Sicherheit und aktiviert den Turm."
                m.special == SpecialMoveType.PROMOTION && m.promoteTo == PieceType.KNIGHT -> "Unterverwandlung zum Springer – starke Taktik-Gabel!"
                m.captured != null -> "Schlägt ${m.captured.type.deName} auf ${m.to.toAlg()} und gewinnt Material."
                ChessRules.isInCheck(state.applyMove(m), state.turn.opposite()) -> "Setzt den gegnerischen König unter Schach!"
                m.to.r in 3..4 && m.to.c in 3..4 -> "Kontrolliert das wichtige Brett-Zentrum (${m.to.toAlg()})."
                else -> "Entwickelt ${m.piece.type.deName} auf ein aktives Feld (${m.to.toAlg()})."
            }
            Triple(m, ev, tip)
        }
    }

    fun analyzePlayedMove(moveNumber: Int, boardBefore: BoardState, move: Move, boardAfter: BoardState): AnalyzedMove {
        if (move.isPseudoLegalOnly) {
            val (best, _) = findBestMove(boardBefore, 2)
            return AnalyzedMove(
                moveNumber = moveNumber,
                move = move,
                boardBefore = boardBefore,
                boardAfter = boardAfter,
                evalBefore = evaluate(boardBefore),
                evalAfter = -99999,
                bestMove = best,
                badge = MoveBadge.ILLEGAL_SUICIDE,
                explanation = "REGEL-VERSTOSS IM HARDCORE-MODUS: ${move.illegalReason} Stattdessen wäre ${best?.toNotation() ?: "ein Königsschutz"} Pflicht gewesen!"
            )
        }

        val isWhite = move.piece.color == PieceColor.WHITE
        val (bestMove, bestEvalAfter) = findBestMove(boardBefore, 2)
        val actualEvalAfter = minimax(boardAfter, 1, Int.MIN_VALUE, Int.MAX_VALUE, !isWhite)
        val evalBefore = evaluate(boardBefore)

        val cpLoss = if (isWhite) (bestEvalAfter - actualEvalAfter) else (actualEvalAfter - bestEvalAfter)
        val isSameAsBest = bestMove != null && bestMove.from == move.from && bestMove.to == move.to && bestMove.promoteTo == move.promoteTo
        val isSpecialTactic = move.special == SpecialMoveType.EN_PASSANT ||
                (move.special == SpecialMoveType.PROMOTION && move.promoteTo == PieceType.KNIGHT) ||
                (move.captured == null && ChessRules.isSquareAttacked(boardAfter, move.to, move.piece.color.opposite()) && cpLoss <= 30)

        val badge = when {
            isSameAsBest && isSpecialTactic -> MoveBadge.BRILLIANT
            isSameAsBest && cpLoss <= 10 -> MoveBadge.BEST
            cpLoss <= 35 -> MoveBadge.GOOD
            cpLoss <= 110 -> MoveBadge.INACCURACY
            cpLoss <= 260 -> MoveBadge.MISTAKE
            else -> MoveBadge.BLUNDER
        }

        val explanation = when (badge) {
            MoveBadge.BRILLIANT -> "BRILLANTER ZUG! Mit ${move.toNotation()} spielst du eine versteckte Taktik / ein mutiges Opfer, das die Stellung komplett zu deinen Gunsten dreht."
            MoveBadge.BEST -> "Perfekt! ${move.toNotation()} ist genau der stärkste Zug in dieser Stellung."
            MoveBadge.GOOD -> "Solider Zug (${move.toNotation()}). Hält die Stellung sauber zusammen."
            MoveBadge.INACCURACY -> "Kleine Ungenauigkeit: ${move.toNotation()} lässt dem Gegner etwas Gegenspiel. ${bestMove?.toNotation() ?: ""} war präziser."
            MoveBadge.MISTAKE -> "Fehler! Nach ${move.toNotation()} verlierst du spürbar an Vorteil. Besser wäre ${bestMove?.toNotation() ?: "ein aktiverer Zug"} gewesen."
            MoveBadge.BLUNDER -> "GROBER BLUNDER! ${move.toNotation()} übersieht eine direkte Drohung oder stellt Material ein. Mit ${bestMove?.toNotation() ?: ""} hättest du die Partie im Griff gehabt."
            else -> ""
        }

        return AnalyzedMove(moveNumber, move, boardBefore, boardAfter, evalBefore, actualEvalAfter, bestMove, badge, explanation)
    }
}

// ============================================================================
// 3. INTERAKTIVE SPEZIALREGELN & LEKTIONEN
// ============================================================================

data class ChessLesson(
    val title: String,
    val subtitle: String,
    val tag: String,
    val taskPrompt: String,
    val hint: String,
    val successExplanation: String,
    val initialState: BoardState,
    val expectedFrom: Pos,
    val expectedTo: Pos,
    val expectedPromote: PieceType? = null
)

object LessonRepository {
    fun getAllLessons(): List<ChessLesson> {
        fun emptyGrid() = MutableList(8) { MutableList<Piece?>(8) { null } }

        val g1 = emptyGrid().apply {
            this[7][4] = Piece(PieceType.KING, PieceColor.WHITE, true)
            this[0][4] = Piece(PieceType.KING, PieceColor.BLACK, true)
            this[3][4] = Piece(PieceType.PAWN, PieceColor.WHITE, true)
            this[3][3] = Piece(PieceType.PAWN, PieceColor.BLACK, true)
        }
        val lesson1 = ChessLesson(
            title = "1. En Passant (Im Vorbeigehen)",
            subtitle = "Sieht aus wie Cheaten – ist aber Pflichtwissen!",
            tag = "SPEZIALREGEL",
            taskPrompt = "Schwarz hat gerade seinen Bauern von d7 auf d5 gezogen. Schlage ihn mit deinem Bauern auf e5 'im Vorbeigehen'!",
            hint = "Ziehe deinen weißen Bauern diagonal auf das leere Feld d6 hinter dem schwarzen Bauern.",
            successExplanation = "Stark! En Passant geht NUR sofort im allerersten Zug nach dem Doppelschritt des Gegners – wartest du einen Zug, verfällt das Recht!",
            initialState = BoardState(g1.map { it.toList() }, PieceColor.WHITE, enPassantTarget = Pos(2, 3)),
            expectedFrom = Pos(3, 4),
            expectedTo = Pos(2, 3)
        )

        val g2 = emptyGrid().apply {
            this[3][0] = Piece(PieceType.KING, PieceColor.WHITE, true)
            this[3][7] = Piece(PieceType.ROOK, PieceColor.BLACK, true)
            this[3][4] = Piece(PieceType.PAWN, PieceColor.WHITE, true)
            this[3][3] = Piece(PieceType.PAWN, PieceColor.BLACK, true)
            this[0][4] = Piece(PieceType.KING, PieceColor.BLACK, true)
        }
        val lesson2 = ChessLesson(
            title = "2. Die verbotene En-Passant-Falle",
            subtitle = "Warum En Passant hier Selbstmord wäre",
            tag = "HARDCORE-FALLE",
            taskPrompt = "Dein König steht auf a5, der schwarze Turm auf h5! Wenn du jetzt En Passant schlägst, verschwinden BEIDE Bauern von Reihe 5. Rette stattdessen deinen König!",
            hint = "Schlage NICHT En Passant (sonst knallt der Turm auf h5 deinen König weg!). Ziehe deinen König nach a6.",
            successExplanation = "Genau erkannt! Im Hardcore-Modus hättest du mit En Passant beide Bauern entfernt und dem Turm auf h5 freie Schussbahn auf deinen König gegeben!",
            initialState = BoardState(g2.map { it.toList() }, PieceColor.WHITE, enPassantTarget = Pos(2, 3)),
            expectedFrom = Pos(3, 0),
            expectedTo = Pos(2, 0)
        )

        // Bauer auf f7 (1,5) -> f8 (0,5) verwandelt in Springer (♘) -> gabelt König auf g6 (2,6) und Dame auf d7 (1,3)!
        val g3 = emptyGrid().apply {
            this[2][6] = Piece(PieceType.KING, PieceColor.BLACK, true)
            this[1][3] = Piece(PieceType.QUEEN, PieceColor.BLACK, true)
            this[1][5] = Piece(PieceType.PAWN, PieceColor.WHITE, true)
            this[7][4] = Piece(PieceType.KING, PieceColor.WHITE, true)
        }
        val lesson3 = ChessLesson(
            title = "3. Unterverwandlung zum Springer",
            subtitle = "Warum eine Dame hier falsch wäre!",
            tag = "PROFI-MOVE",
            taskPrompt = "Ziehe deinen Bauern auf f8 und verwandle ihn in einen SPRINGER, um König (g6) und Dame (d7) gleichzeitig zu gabeln!",
            hint = "Ziehe f7 -> f8 und wähle im Popup den Springer (♘)!",
            successExplanation = "Königliche Gabel! Der Springer auf f8 greift gleichzeitig den König auf g6 und die Dame auf d7 an.",
            initialState = BoardState(g3.map { it.toList() }, PieceColor.WHITE),
            expectedFrom = Pos(1, 5),
            expectedTo = Pos(0, 5),
            expectedPromote = PieceType.KNIGHT
        )

        val g4 = emptyGrid().apply {
            this[7][4] = Piece(PieceType.KING, PieceColor.WHITE, false)
            this[7][7] = Piece(PieceType.ROOK, PieceColor.WHITE, false)
            this[0][7] = Piece(PieceType.ROOK, PieceColor.BLACK, true)
            this[0][4] = Piece(PieceType.KING, PieceColor.BLACK, true)
        }
        val lesson4 = ChessLesson(
            title = "4. Rochade-Mythos zerstört",
            subtitle = "Darf man rochieren, wenn der Turm angegriffen wird?",
            tag = "SPEZIALREGEL",
            taskPrompt = "Der schwarze Turm auf h8 greift deinen Turm auf h1 an. Viele denken, Rochade sei jetzt verboten – mach die kurze Rochade (O-O)!",
            hint = "Ziehe deinen König von e1 zwei Felder nach rechts auf g1.",
            successExplanation = "Richtig! Nur der KÖNIG darf nicht im Schach stehen oder über bedrohte Felder ziehen. Ob der Turm angegriffen ist, ist völlig egal!",
            initialState = BoardState(g4.map { it.toList() }, PieceColor.WHITE),
            expectedFrom = Pos(7, 4),
            expectedTo = Pos(7, 6)
        )

        val g5 = emptyGrid().apply {
            this[0][4] = Piece(PieceType.KING, PieceColor.BLACK, true)
            this[2][4] = Piece(PieceType.KNIGHT, PieceColor.BLACK, true)
            this[6][4] = Piece(PieceType.ROOK, PieceColor.WHITE, true)
            this[7][4] = Piece(PieceType.KING, PieceColor.WHITE, true)
            this[4][3] = Piece(PieceType.PAWN, PieceColor.WHITE, true)
        }
        val lesson5 = ChessLesson(
            title = "5. Absolute Fesselung (The Pin)",
            subtitle = "Eine gefesselte Figur kann sich nicht wehren",
            tag = "TAKTIK",
            taskPrompt = "Der schwarze Springer auf e6 ist durch deinen Turm auf e2 an seinen König gefesselt. Greife ihn mit deinem Bauern auf d4 an!",
            hint = "Ziehe deinen Bauern von d4 auf d5 – der Springer kann nicht fliehen, weil sonst sein König im Schach steht!",
            successExplanation = "Perfekt! Gefesselte Figuren sind wie gelähmt – greif sie immer mit Bauern an!",
            initialState = BoardState(g5.map { it.toList() }, PieceColor.WHITE),
            expectedFrom = Pos(4, 3),
            expectedTo = Pos(3, 3)
        )

        return listOf(lesson1, lesson2, lesson3, lesson4, lesson5)
    }
}

// ============================================================================
// 4. UI & INTERAKTIVES BRETT
// ============================================================================

enum class AppScreen { PLAY, LESSONS, ANALYSIS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF121417),
                    surface = Color(0xFF1C2026),
                    primary = Color(0xFF81B64C),
                    secondary = Color(0xFF1BACA6),
                    error = Color(0xFFFF453A)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ChessMasterApp()
                }
            }
        }
    }
}

data class BoardArrow(val from: Pos, val to: Pos, val color: Color)

@Composable
fun ChessMasterApp() {
    var currentScreen by remember { mutableStateOf(AppScreen.PLAY) }
    var hardcoreMode by remember { mutableStateOf(true) }
    var boardState by remember { mutableStateOf(BoardState.initial()) }
    var selectedSquare by remember { mutableStateOf<Pos?>(null) }
    var pendingPromoMove by remember { mutableStateOf<Pair<Pos, Pos>?>(null) }
    var moveHistory by remember { mutableStateOf<List<AnalyzedMove>>(emptyList()) }
    var gameOverTitle by remember { mutableStateOf<String?>(null) }
    var gameOverReason by remember { mutableStateOf<String?>(null) }
    var coachTips by remember { mutableStateOf<List<Triple<Move, Int, String>>>(emptyList()) }
    var showCoachArrows by remember { mutableStateOf(false) }
    var botDepth by remember { mutableIntStateOf(2) }
    var activeLesson by remember { mutableStateOf<ChessLesson?>(null) }
    var lessonFeedback by remember { mutableStateOf<String?>(null) }
    var reviewIndex by remember { mutableIntStateOf(0) }

    val scope = rememberCoroutineScope()

    fun resetGame() {
        boardState = BoardState.initial()
        selectedSquare = null
        moveHistory = emptyList()
        gameOverTitle = null
        gameOverReason = null
        coachTips = emptyList()
        showCoachArrows = false
    }

    fun executePlayerMove(move: Move) {
        if (gameOverTitle != null) return

        val lesson = activeLesson
        if (lesson != null) {
            if (move.isPseudoLegalOnly) {
                lessonFeedback = "💀 IM HARDCORE-MODUS WÄRST DU JETZT TOT! ${move.illegalReason}"
                return
            }
            if (move.from == lesson.expectedFrom && move.to == lesson.expectedTo &&
                (lesson.expectedPromote == null || move.promoteTo == lesson.expectedPromote)
            ) {
                boardState = boardState.applyMove(move)
                selectedSquare = null
                lessonFeedback = "✅ ${lesson.successExplanation}"
            } else {
                lessonFeedback = "❌ Nicht ganz! Tipp: ${lesson.hint}"
            }
            return
        }

        val before = boardState
        val after = before.applyMove(move)
        val analyzed = ChessAIAndCoach.analyzePlayedMove(moveHistory.size + 1, before, move, after)
        moveHistory = moveHistory + analyzed
        boardState = after
        selectedSquare = null
        showCoachArrows = false

        if (move.isPseudoLegalOnly) {
            val enemyMoves = ChessRules.generateMoves(after, PieceColor.BLACK, hardcoreMode = true)
            val whiteKingPos = after.findKing(PieceColor.WHITE)
            val kingCaptureMove = enemyMoves.find { it.to == whiteKingPos }
            if (kingCaptureMove != null) {
                boardState = after.applyMove(kingCaptureMove)
            }
            gameOverTitle = "💀 KÖNIG GESCHLAGEN – HARDCORE GAME OVER!"
            gameOverReason = move.illegalReason
            return
        }

        val blackLegal = ChessRules.generateMoves(after, PieceColor.BLACK, hardcoreMode = false)
        if (blackLegal.isEmpty()) {
            if (ChessRules.isInCheck(after, PieceColor.BLACK)) {
                gameOverTitle = "🏆 SCHACHMATT! DU HAST GEWONNEN!"
                gameOverReason = "Starker Abschluss – Schwarz hat keinen legalen Zug mehr."
            } else {
                gameOverTitle = "🤝 UNENTSCHIEDEN (PATT)"
                gameOverReason = "Schwarz steht nicht im Schach, kann aber nirgendwo hinziehen."
            }
            return
        }

        scope.launch {
            delay(280)
            if (gameOverTitle != null) return@launch
            val (aiMove, _) = ChessAIAndCoach.findBestMove(boardState, botDepth)
            if (aiMove != null) {
                val aiBefore = boardState
                val aiAfter = aiBefore.applyMove(aiMove)
                val aiAnalyzed = ChessAIAndCoach.analyzePlayedMove(moveHistory.size + 1, aiBefore, aiMove, aiAfter)
                moveHistory = moveHistory + aiAnalyzed
                boardState = aiAfter

                val whiteLegal = ChessRules.generateMoves(aiAfter, PieceColor.WHITE, hardcoreMode = false)
                if (whiteLegal.isEmpty()) {
                    if (ChessRules.isInCheck(aiAfter, PieceColor.WHITE)) {
                        gameOverTitle = "💀 SCHACHMATT – VERLOREN!"
                        gameOverReason = "Der Bot hat deinen König matt gesetzt. Schau in die Analyse!"
                    } else {
                        gameOverTitle = "🤝 UNENTSCHIEDEN (PATT)"
                        gameOverReason = "Du stehst nicht im Schach, hast aber keinen Zug mehr."
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF181B20))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("♟ CHESSMASTER", fontWeight = FontWeight.Black, fontSize = 18.sp, color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (hardcoreMode) "💀 HARDCORE AN" else "🛡 SAFE-MODUS",
                    color = if (hardcoreMode) Color(0xFFFF5252) else Color(0xFF81B64C),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Spacer(Modifier.width(6.dp))
                Switch(
                    checked = hardcoreMode,
                    onCheckedChange = { hardcoreMode = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF1744))
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1C2026))
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            NavChip("Spielen & Coach", currentScreen == AppScreen.PLAY && activeLesson == null) {
                activeLesson = null
                currentScreen = AppScreen.PLAY
            }
            NavChip("Spezial-Regeln", currentScreen == AppScreen.LESSONS || activeLesson != null) {
                currentScreen = AppScreen.LESSONS
            }
            NavChip("Analyse (${moveHistory.size})", currentScreen == AppScreen.ANALYSIS) {
                reviewIndex = max(0, moveHistory.size - 1)
                currentScreen = AppScreen.ANALYSIS
            }
        }

        when {
            currentScreen == AppScreen.PLAY -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (activeLesson != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF232830)),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(activeLesson!!.title, fontWeight = FontWeight.Bold, color = Color(0xFF1BACA6))
                                    Text("Beenden ✕", color = Color.Gray, modifier = Modifier.clickable {
                                        activeLesson = null
                                        resetGame()
                                    })
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(activeLesson!!.taskPrompt, fontSize = 13.sp, color = Color.White)
                                if (lessonFeedback != null) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        lessonFeedback!!,
                                        color = if (lessonFeedback!!.startsWith("✅")) Color(0xFF81B64C) else Color(0xFFFF5252),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🤖 Bot-Stärke:", color = Color.LightGray, fontSize = 13.sp)
                            Row {
                                listOf(1 to "Easy", 2 to "Mittel", 3 to "Stark").forEach { (d, label) ->
                                    FilterChip(
                                        selected = botDepth == d,
                                        onClick = { botDepth = d },
                                        label = { Text(label, fontSize = 12.sp) },
                                        modifier = Modifier.padding(horizontal = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    val currentMoves = remember(boardState, hardcoreMode, activeLesson) {
                        ChessRules.generateMoves(boardState, boardState.turn, hardcoreMode = hardcoreMode)
                    }
                    val targetSquares = remember(selectedSquare, currentMoves) {
                        if (selectedSquare == null) emptySet()
                        else currentMoves.filter { it.from == selectedSquare }.map { it.to }.toSet()
                    }

                    val arrows = remember(showCoachArrows, coachTips) {
                        if (!showCoachArrows) emptyList()
                        else coachTips.mapIndexed { idx, (m, _, _) ->
                            val col = when (idx) {
                                0 -> Color(0xFF81B64C)
                                1 -> Color(0xFF1BACA6)
                                else -> Color(0xFFF7C631)
                            }
                            BoardArrow(m.from, m.to, col)
                        }
                    }

                    ChessBoardView(
                        state = boardState,
                        selected = selectedSquare,
                        legalTargets = targetSquares,
                        arrows = arrows,
                        lastMove = moveHistory.lastOrNull()?.move,
                        onSquareTap = { pos ->
                            if (gameOverTitle != null) return@ChessBoardView
                            if (boardState.turn != PieceColor.WHITE && activeLesson == null) return@ChessBoardView

                            val sel = selectedSquare
                            if (sel != null && pos in targetSquares) {
                                val matching = currentMoves.filter { it.from == sel && it.to == pos }
                                if (matching.any { it.special == SpecialMoveType.PROMOTION }) {
                                    pendingPromoMove = sel to pos
                                } else if (matching.isNotEmpty()) {
                                    executePlayerMove(matching.first())
                                }
                            } else {
                                val p = boardState.pieceAt(pos)
                                selectedSquare = if (p != null && p.color == boardState.turn) pos else null
                            }
                        }
                    )

                    Spacer(Modifier.height(10.dp))

                    if (gameOverTitle != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1619)),
                            modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFFF5252), RoundedCornerShape(12.dp))
                        ) {
                            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(gameOverTitle!!, fontWeight = FontWeight.Black, fontSize = 16.sp, color = Color(0xFFFF5252), textAlign = TextAlign.Center)
                                Spacer(Modifier.height(4.dp))
                                Text(gameOverReason ?: "", fontSize = 13.sp, color = Color.White, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        reviewIndex = max(0, moveHistory.size - 1)
                                        currentScreen = AppScreen.ANALYSIS
                                    }) {
                                        Text("📊 Zur Post-Game Analyse")
                                    }
                                    OutlinedButton(onClick = { resetGame() }) {
                                        Text("Neu starten")
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    if (activeLesson == null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    coachTips = ChessAIAndCoach.getCoachSuggestions(boardState)
                                    showCoachArrows = true
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1BACA6))
                            ) {
                                Icon(Icons.Default.Lightbulb, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("KI-Coach Tipps", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(onClick = { resetGame() }) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                            }
                        }

                        if (showCoachArrows && coachTips.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2026)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text("🧠 KI-Coach Vorschläge (Pfeile auf dem Brett):", fontWeight = FontWeight.Bold, color = Color(0xFF1BACA6), fontSize = 13.sp)
                                    Spacer(Modifier.height(6.dp))
                                    coachTips.forEachIndexed { idx, (m, _, tip) ->
                                        val dot = when (idx) { 0 -> "🟢"; 1 -> "🔵"; else -> "🟡" }
                                        Text("$dot ${m.toNotation()}: $tip", fontSize = 12.sp, color = Color.White, modifier = Modifier.padding(vertical = 2.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            currentScreen == AppScreen.LESSONS -> {
                val lessons = remember { LessonRepository.getAllLessons() }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text("🎓 Spezialregeln & Hardcore-Fallen", fontWeight = FontWeight.Black, fontSize = 20.sp, color = Color.White)
                        Text("Lerne jeden Move, bei dem andere denken, er wäre illegal – und vermeide tödliche Fallen.", fontSize = 13.sp, color = Color.LightGray)
                    }
                    items(lessons) { lesson ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2026)),
                            modifier = Modifier.fillMaxWidth().clickable {
                                activeLesson = lesson
                                boardState = lesson.initialState
                                selectedSquare = null
                                lessonFeedback = null
                                gameOverTitle = null
                                currentScreen = AppScreen.PLAY
                            }
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(lesson.tag, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1BACA6))
                                Text(lesson.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                                Text(lesson.subtitle, fontSize = 13.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }

            currentScreen == AppScreen.ANALYSIS -> {
                if (moveHistory.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Spiele zuerst ein paar Züge – dann analysiert die KI hier jeden Move!", color = Color.Gray)
                    }
                } else {
                    val safeIdx = reviewIndex.coerceIn(0, moveHistory.size - 1)
                    val item = moveHistory[safeIdx]

                    val whiteMoves = moveHistory.filter { it.move.piece.color == PieceColor.WHITE }
                    val accuracy = if (whiteMoves.isEmpty()) 100 else {
                        val avgScore = whiteMoves.map {
                            when (it.badge) {
                                MoveBadge.BRILLIANT, MoveBadge.BEST -> 100
                                MoveBadge.GREAT -> 95
                                MoveBadge.GOOD -> 85
                                MoveBadge.INACCURACY -> 60
                                MoveBadge.MISTAKE -> 30
                                MoveBadge.BLUNDER, MoveBadge.ILLEGAL_SUICIDE -> 0
                            }
                        }.average()
                        avgScore.toInt()
                    }

                    val analysisArrows = buildList {
                        add(BoardArrow(item.move.from, item.move.to, item.badge.color))
                        if (item.bestMove != null && (item.bestMove.from != item.move.from || item.bestMove.to != item.move.to)) {
                            add(BoardArrow(item.bestMove.from, item.bestMove.to, Color(0xFF81B64C)))
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2026)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$accuracy%", fontWeight = FontWeight.Black, fontSize = 24.sp, color = Color(0xFF81B64C))
                                    Text("Genauigkeit", fontSize = 11.sp, color = Color.Gray)
                                }
                                Text("!! ${whiteMoves.count { it.badge == MoveBadge.BRILLIANT }}", color = MoveBadge.BRILLIANT.color, fontWeight = FontWeight.Bold)
                                Text("? ${whiteMoves.count { it.badge == MoveBadge.MISTAKE }}", color = MoveBadge.MISTAKE.color, fontWeight = FontWeight.Bold)
                                Text("?? ${whiteMoves.count { it.badge == MoveBadge.BLUNDER }}", color = MoveBadge.BLUNDER.color, fontWeight = FontWeight.Bold)
                                Text("💀 ${whiteMoves.count { it.badge == MoveBadge.ILLEGAL_SUICIDE }}", color = MoveBadge.ILLEGAL_SUICIDE.color, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        ChessBoardView(
                            state = item.boardBefore,
                            selected = null,
                            legalTargets = emptySet(),
                            arrows = analysisArrows,
                            lastMove = item.move,
                            onSquareTap = {}
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { if (safeIdx > 0) reviewIndex = safeIdx - 1 }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück", tint = Color.White)
                            }
                            Text("Zug ${item.moveNumber}: ${item.move.toNotation()}", fontWeight = FontWeight.Bold, color = Color.White)
                            IconButton(onClick = { if (safeIdx < moveHistory.size - 1) reviewIndex = safeIdx + 1 }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, content
