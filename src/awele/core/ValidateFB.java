package awele.core;

import java.util.Random;

/**
 * Validate FastBoard against official Board by playing random games
 * and comparing states after each move.
 */
public class ValidateFB {
    // Minimal FastBoard clone for testing (mirrors TieLeOmegaBottMk3.FastBoard)
    static int[] state = new int[14];
    static int currentPlayer;
    
    static void initFromBoard(Board b) {
        int[] h0 = b.getPlayerHoles();
        int[] h1 = b.getOpponentHoles();
        int cp = b.getCurrentPlayer();
        if (cp == 0) {
            System.arraycopy(h0, 0, state, 0, 6);
            System.arraycopy(h1, 0, state, 6, 6);
        } else {
            System.arraycopy(h1, 0, state, 0, 6);
            System.arraycopy(h0, 0, state, 6, 6);
        }
        state[12] = b.getScore(0);
        state[13] = b.getScore(1);
        currentPlayer = cp;
    }
    
    static void makeMove(int hole) {
        int offset = (currentPlayer == 0) ? 0 : 6;
        int index = offset + hole;
        int seeds = state[index];
        state[index] = 0;
        
        int ptr = index;
        while (seeds > 0) {
            ptr++;
            if (ptr >= 12) ptr = 0;
            if (ptr == index) continue;
            state[ptr]++;
            seeds--;
        }
        
        boolean isOppSide = (currentPlayer == 0) ? (ptr >= 6) : (ptr < 6);
        if (isOppSide) {
            int tempPtr = ptr;
            int capturedSum = 0;
            while (true) {
                boolean inOpp = (currentPlayer == 0) ? (tempPtr >= 6) : (tempPtr < 6);
                if (!inOpp) break;
                int s = state[tempPtr];
                if (s == 2 || s == 3) {
                    capturedSum += s;
                    tempPtr--;
                    if (tempPtr < 0) tempPtr = 11;
                } else break;
            }
            if (capturedSum > 0) {
                int oppStart = (currentPlayer == 0) ? 6 : 0;
                int seedsTotalOpp = 0;
                for (int i = 0; i < 6; i++) seedsTotalOpp += state[oppStart + i];
                if (seedsTotalOpp > capturedSum) {
                    state[12 + currentPlayer] += capturedSum;
                    tempPtr = ptr;
                    while (true) {
                        boolean inOpp2 = (currentPlayer == 0) ? (tempPtr >= 6) : (tempPtr < 6);
                        if (!inOpp2) break;
                        int s = state[tempPtr];
                        if (s == 2 || s == 3) {
                            state[tempPtr] = 0;
                            tempPtr--;
                            if (tempPtr < 0) tempPtr = 11;
                        } else break;
                    }
                }
            }
        }
        currentPlayer = 1 - currentPlayer;
    }
    
    static int getLegalMoveCount() {
        int oppOffset = (currentPlayer == 0) ? 6 : 0;
        int oppSeeds = 0;
        for (int i = 0; i < 6; i++) oppSeeds += state[oppOffset + i];
        boolean mustFeed = (oppSeeds == 0);
        int offset = (currentPlayer == 0) ? 0 : 6;
        int count = 0;
        for (int i = 0; i < 6; i++) {
            int seeds = state[offset + i];
            if (seeds == 0) continue;
            if (mustFeed && seeds < (6 - i)) continue;
            count++;
        }
        return count;
    }
    
    static int getLegalMove(int idx) {
        int oppOffset = (currentPlayer == 0) ? 6 : 0;
        int oppSeeds = 0;
        for (int i = 0; i < 6; i++) oppSeeds += state[oppOffset + i];
        boolean mustFeed = (oppSeeds == 0);
        int offset = (currentPlayer == 0) ? 0 : 6;
        int count = 0;
        for (int i = 0; i < 6; i++) {
            int seeds = state[offset + i];
            if (seeds == 0) continue;
            if (mustFeed && seeds < (6 - i)) continue;
            if (count == idx) return i;
            count++;
        }
        return -1;
    }
    
    static boolean compare(Board board) {
        int cp = board.getCurrentPlayer();
        int[] h0, h1;
        if (cp == 0) {
            h0 = board.getPlayerHoles();
            h1 = board.getOpponentHoles();
        } else {
            h1 = board.getPlayerHoles();
            h0 = board.getOpponentHoles();
        }
        
        for (int i = 0; i < 6; i++) {
            if (state[i] != h0[i]) return false;
            if (state[6+i] != h1[i]) return false;
        }
        if (state[12] != board.getScore(0)) return false;
        if (state[13] != board.getScore(1)) return false;
        if (currentPlayer != cp) return false;
        return true;
    }
    
    public static void main(String[] args) throws Exception {
        Random rng = new Random(42);
        int gamesPlayed = 0;
        int totalMoves = 0;
        int mismatches = 0;
        
        for (int game = 0; game < 10000; game++) {
            Board board = new Board();
            board.setCurrentPlayer(0);
            initFromBoard(board);
            
            for (int turn = 0; turn < 200; turn++) {
                int cp = board.getCurrentPlayer();
                boolean[] valid = board.validMoves(cp);
                int nValid = 0;
                for (boolean v : valid) if (v) nValid++;
                
                if (nValid == 0) break;
                int fbMoves = getLegalMoveCount();
                if (fbMoves != nValid) {
                    System.out.println("MISMATCH: Legal move count! Board=" + nValid + " FB=" + fbMoves);
                    System.out.println("  Board cp=" + cp + " FB cp=" + currentPlayer);
                    for (int i = 0; i < 6; i++) System.out.print(state[i] + ",");
                    System.out.print("|");
                    for (int i = 6; i < 12; i++) System.out.print(state[i] + ",");
                    System.out.println(" scores=" + state[12] + "-" + state[13]);
                    mismatches++;
                    break;
                }
                
                // Pick random valid move
                int moveIdx = rng.nextInt(nValid);
                int move = getLegalMove(moveIdx);
                
                // Play on both
                try {
                    double[] dec = new double[6];
                    dec[move] = 1.0;
                    board.playMove(cp, dec);
                } catch (InvalidBotException e) {
                    System.out.println("InvalidBotException at game " + game + " turn " + turn);
                    break;
                }
                makeMove(move);
                totalMoves++;
                
                if (!compare(board)) {
                    System.out.println("MISMATCH after move " + move + " at game " + game + " turn " + turn);
                    System.out.print("  FB: [");
                    for (int i = 0; i < 12; i++) System.out.print(state[i] + (i<11?",":""));
                    System.out.println("] scores=" + state[12] + "-" + state[13] + " cp=" + currentPlayer);
                    int bcp = board.getCurrentPlayer();
                    int[] bh0 = (bcp == 0) ? board.getPlayerHoles() : board.getOpponentHoles();
                    int[] bh1 = (bcp == 0) ? board.getOpponentHoles() : board.getPlayerHoles();
                    System.out.print("  BD: [");
                    for (int i = 0; i < 6; i++) System.out.print(bh0[i] + ",");
                    for (int i = 0; i < 6; i++) System.out.print(bh1[i] + (i<5?",":""));
                    System.out.println("] scores=" + board.getScore(0) + "-" + board.getScore(1) + " cp=" + bcp);
                    mismatches++;
                    break;
                }
                
                // Check game over
                if (board.getScore(0) >= 25 || board.getScore(1) >= 25 || board.getNbSeeds() <= 6)
                    break;
            }
            gamesPlayed++;
        }
        System.out.println("Validated " + gamesPlayed + " games, " + totalMoves + " moves.");
        System.out.println("Mismatches: " + mismatches);
    }
}
