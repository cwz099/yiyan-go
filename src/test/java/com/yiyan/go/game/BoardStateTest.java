package com.yiyan.go.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class BoardStateTest {
    @Test
    void newGameIsEmptyAndBlackStarts() {
        BoardState game = new BoardState();
        assertEquals(19, game.size(), "默认应为 19 路");
        assertEquals(Stone.BLACK, game.turn(), "黑棋应先行");
        assertEquals(0, game.moveNumber(), "新局手数应为 0");
        assertEquals(Stone.EMPTY, game.stoneAt(3, 3), "棋盘应为空");
    }

    @Test
    void occupiedPointIsRejectedWithoutMutation() {
        BoardState game = new BoardState(5);
        assertTrue(game.play(2, 2).success(), "第一手应合法");
        MoveResult rejected = game.play(2, 2);
        assertFalse(rejected.success(), "不能落在已有棋子的交叉点");
        assertEquals(1, game.moveNumber(), "非法尝试不能增加手数");
        assertEquals(Stone.WHITE, game.turn(), "非法尝试不能切换行棋方");
    }

    @Test
    void singleStoneCaptureRemovesStone() {
        BoardState game = new BoardState(5);
        play(game, 1, 1); // B target
        play(game, 0, 1); // W
        play(game, 4, 4); // B filler
        play(game, 1, 0); // W
        play(game, 4, 3); // B filler
        play(game, 2, 1); // W
        play(game, 3, 4); // B filler
        MoveResult capture = game.play(1, 2); // W closes the last liberty
        assertTrue(capture.success(), "包围着手应合法");
        assertEquals(1, capture.captured().size(), "应提掉一子");
        assertEquals(Stone.EMPTY, game.stoneAt(1, 1), "被提棋子应从棋盘移除");
        assertEquals(1, game.whiteCaptures(), "白棋提子数应增加");
    }

    @Test
    void suicideIsRejectedWithoutMutation() {
        BoardState game = new BoardState(5);
        play(game, 4, 4); // B filler
        play(game, 0, 1); // W surrounds center
        play(game, 4, 3);
        play(game, 1, 0);
        play(game, 3, 4);
        play(game, 2, 1);
        play(game, 3, 3);
        play(game, 1, 2);
        int before = game.moveNumber();
        MoveResult result = game.play(1, 1);
        assertFalse(result.success(), "无气且未提子的着手应判自杀");
        assertEquals(before, game.moveNumber(), "自杀禁入不能改变状态");
        assertEquals(Stone.EMPTY, game.stoneAt(1, 1), "禁入点应保持为空");
    }

    @Test
    void oneMoveCanCaptureTwoGroups() {
        BoardState game = new BoardState(5);
        play(game, 0, 2); // B surrounds the left target
        assertTrue(game.pass().success(), "白棋停一手");
        play(game, 1, 1);
        assertTrue(game.pass().success(), "白棋停一手");
        play(game, 1, 3);
        play(game, 1, 2); // W left target, only center liberty remains
        play(game, 4, 2); // B surrounds the right target
        assertTrue(game.pass().success(), "白棋停一手");
        play(game, 3, 1);
        assertTrue(game.pass().success(), "白棋停一手");
        play(game, 3, 3);
        play(game, 3, 2); // W right target, only center liberty remains

        MoveResult capture = game.play(2, 2);
        assertTrue(capture.success(), "中心落子应同时提掉两边棋子");
        assertEquals(2, capture.captured().size(), "两个独立棋块都应被提掉且只计一次");
        assertEquals(Stone.EMPTY, game.stoneAt(1, 2), "左侧白子应被提掉");
        assertEquals(Stone.EMPTY, game.stoneAt(3, 2), "右侧白子应被提掉");
        assertEquals(2, game.blackCaptures(), "黑棋提子数应增加两子");
    }

    @Test
    void immediateKoRecaptureIsRejected() {
        BoardState game = createKoPosition();
        MoveResult capture = game.play(2, 1);
        assertTrue(capture.success(), "劫争提子应合法");
        assertEquals(1, capture.captured().size(), "劫争应提一子");
        MoveResult recapture = game.play(1, 1);
        assertFalse(recapture.success(), "立即回提会还原上一局面，应被拒绝");
        assertEquals(Stone.EMPTY, game.stoneAt(1, 1), "非法回提不能改变棋盘");
        assertEquals(Stone.BLACK, game.stoneAt(2, 1), "劫材位置应保留黑子");
    }

    private BoardState createKoPosition() {
        BoardState game = new BoardState(5);
        play(game, 1, 0); // B
        play(game, 2, 0); // W
        play(game, 0, 1); // B
        play(game, 3, 1); // W
        play(game, 1, 2); // B
        play(game, 2, 2); // W
        assertTrue(game.pass().success(), "黑棋停一手用于搭建测试局面");
        play(game, 1, 1); // W, leaves one liberty at (2,1)
        assertEquals(Stone.BLACK, game.turn(), "搭建后应轮到黑棋");
        return game;
    }

    @Test
    void koRecaptureAfterInterveningMovesIsLegal() {
        BoardState game = createKoPosition();
        play(game, 2, 1); // Black captures the ko.
        play(game, 4, 4); // White ko threat.
        play(game, 4, 3); // Black answers elsewhere.
        MoveResult recapture = game.play(1, 1);
        assertTrue(recapture.success(), "双方在别处各走一手后应允许回提");
        assertEquals(1, recapture.captured().size(), "回提应提掉一颗黑子");
        assertEquals(Stone.EMPTY, game.stoneAt(2, 1), "被回提的黑子应移除");
    }

    @Test
    void normalMoveResetsPassCount() {
        BoardState game = new BoardState(5);
        assertTrue(game.pass().success(), "第一次停一手应合法");
        assertEquals(1, game.consecutivePasses(), "连续停一手计数应为 1");
        play(game, 2, 2);
        assertEquals(0, game.consecutivePasses(), "普通落子应重置停一手计数");
        assertFalse(game.gameOver(), "停一手后继续落子不应结束棋局");
    }

    @Test
    void twoPassesEndGame() {
        BoardState game = new BoardState(5);
        assertTrue(game.pass().success(), "黑棋停一手应合法");
        assertTrue(game.pass().success(), "白棋停一手应合法");
        assertTrue(game.gameOver(), "双方连续停一手应结束棋局");
        assertFalse(game.play(2, 2).success(), "终局后不能继续落子");
    }

    @Test
    void coordinatesSkipLetterI() {
        assertEquals("H19", new Point(7, 0).coordinate(19), "H 列坐标应正确");
        assertEquals("J19", new Point(8, 0).coordinate(19), "围棋坐标应跳过 I");
        assertEquals(new Point(15, 9), Point.fromCoordinate("Q10", 19), "坐标解析应可逆");
    }

    @Test
    void copiesAreIndependent() {
        BoardState original = new BoardState(5);
        BoardState copy = original.copy();
        play(copy, 2, 2);
        assertEquals(Stone.EMPTY, original.stoneAt(2, 2), "副本落子不能污染原局面");
        assertEquals(0, original.moveNumber(), "原局面手数应保持不变");
    }

    @Test
    void asciiDiagramUsesActualBoardSize() {
        BoardState game = new BoardState(5);
        String diagram = game.asciiDiagram();
        assertTrue(diagram.endsWith("A B C D E"), "5 路棋盘只应输出五个列标");
        assertEquals(6L, diagram.lines().count(), "5 路棋盘应包含五行棋面和一行列标");
    }

    private void play(BoardState game, int x, int y) {
        MoveResult result = game.play(x, y);
        assertTrue(result.success(),
                () -> "测试局面落子应合法: (" + x + "," + y + ")，实际：" + result.message());
    }
}
