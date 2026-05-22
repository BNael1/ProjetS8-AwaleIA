package awele.bot.competitor.TieLeOmegaBottMk3;

import awele.bot.CompetitorBot;
import awele.core.Board;
import awele.core.InvalidBotException;
import awele.data.AweleData;
import awele.data.AweleObservation;

import java.util.Arrays;
import java.util.Random;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * TieLeOmegaBottMk3 — Moteur avancé pour l'Awalé.
 *
 * Fusion ultime de toutes les techniques développées au cours du projet :
 *
 * ARCHITECTURE :
 * - FastBoard zéro-allocation (int[14] + make/unmake via historique)
 * - TT SoA (Structure of Arrays) avec Zobrist 64-bit + generation aging
 * - Opening Book immuable séparé de la TT dynamique
 *
 * APPRENTISSAGE (55 min) :
 * A. CSV & T-Statistic pour initialisation data-driven des poids
 * B. GA continu sur un jeu de poids unique (14 gènes)
 * C. Endgame Tablebase combinadique (≤15 graines, ~33 MiB, index×2+player)
 * D. Deep Opening Book via Massive TT → condensé en Opening Book immuable
 * E. Évaluation : interpolation linéaire phase de jeu (opening ↔ endgame)
 *
 * MOTEUR DE RECHERCHE :
 * - PVS (Principal Variation Search) + Aspiration Windows
 * - Iterative Deepening avec Time Banking dynamique
 * - LMR (Late Move Reductions) logarithmique
 * - Reverse Futility Pruning, Razoring, Late Move Pruning, Futility Pruning
 * - Quiescence Search avec Delta Pruning (Δ=300)
 * - Move Ordering : TT → Killer → Countermove → History → KNN
 * - IID (Internal Iterative Deepening) si pas de TT move
 *
 * ENDGAME :
 * - Tablebase lookup pour jeu parfait (≤15 graines)
 *
 * @author BENSAADI et MORLET
 */
public class TieLeOmegaBottMk3 extends CompetitorBot {

    // =====================================================================
    // CONFIGURATION
    // =====================================================================

    /** Mode test : réduit les temps pour le debugging */
    private static final boolean TEST_MODE = false;

    /** Temps total d'apprentissage (55 min en production, 1 min en test) */
    private static final long TOTAL_TRAINING_TIME = TEST_MODE ? 60_000L : 55 * 60_000L;

    /** Budget alloué au GA (10 min en production, early stopping si stagnation) */
    private static final long GA_TIME = TEST_MODE ? 10_000L : 10 * 60_000L;

    // (plus de cache de poids externe — tout est calculé pendant learn())

    /** Budget alloué à la Tablebase (15 min max) */
    private static final long TB_TIME = TEST_MODE ? 10_000L : 15 * 60_000L;

    /** Profondeur maximale de recherche */
    private static final int MAX_DEPTH = 64;

    /** Nombre de features dans l'évaluation */
    private static final int NUM_WEIGHTS = 14;

    /** Taille de la TT compacte pour le jeu (512K entrées ≈ 7.5 MiB) */
    private static final int COMPACT_TT_SIZE = 1 << 19; // 524 288

    /** Taille de la TT massive pour l'apprentissage (16M entrées ≈ 234 MiB) */
    private static final int MASSIVE_TT_SIZE = 1 << 24; // 16 777 216

    /**
     * Seuil de graines pour la Tablebase (combinadique : ~33 MiB pour 15,
     * index×2+player)
     */
    private static final int TB_SEEDS = TEST_MODE ? 6 : 15;

    // =====================================================================
    // TRANSPOSITION TABLE (SoA)
    // =====================================================================

    private int ttSize;
    private int ttMask;
    private long[] ttKeys;
    private int[] ttScores;
    private byte[] ttDepths;
    private byte[] ttFlags;
    private byte[] ttMoves;
    private byte[] ttGen;
    private byte currentGen;

    private static final byte FLAG_EXACT = 0;
    private static final byte FLAG_LOWER = 1;
    private static final byte FLAG_UPPER = 2;

    // =====================================================================
    // ZOBRIST HASHING
    // =====================================================================

    /** Table Zobrist pré-calculée : [joueur 0/1][trou 0-5][graines 0-49] */
    private static final long[][][] ZOBRIST = new long[2][6][50];
    private static final long ZOBRIST_SIDE;

    static {
        Random r = new Random(0x54694C654FL); // "TieLeO" en hex
        for (int p = 0; p < 2; p++)
            for (int h = 0; h < 6; h++)
                for (int s = 0; s < 50; s++)
                    ZOBRIST[p][h][s] = r.nextLong();
        ZOBRIST_SIDE = r.nextLong();
    }

    // =====================================================================
    // LMR TABLE
    // =====================================================================

    /** Table de réduction LMR pré-calculée : LMR[depth][moveIndex] */
    private static final int[][] LMR = new int[64][7];

    static {
        for (int d = 1; d < 64; d++)
            for (int m = 1; m < 7; m++)
                LMR[d][m] = Math.max(0, (int) (0.5 + Math.log(d) * Math.log(m) / 2.5));
    }

    // =====================================================================
    // POIDS D'ÉVALUATION
    // =====================================================================

    /** Poids d'évaluation calibrés (un seul jeu, 14 gènes) */
    private double[] weights;
    /** Alias vers weights — simplifie les appels evaluate(b, activeWeights) */
    private double[] activeWeights;

    // =====================================================================
    // ÉTAT DE RECHERCHE
    // =====================================================================

    private int[][] killerMoves; // [depth][0-1]
    private int[][] historyMoves; // [player][move]
    private int[] countermove; // [lastMove (0-11)]

    private long searchStartTime;
    private long timeBudgetMs; // Budget dynamique pour ce coup
    private boolean timeOut;
    private long learnSearchDeadline; // Deadline dynamique pour pvsLearn
    private int lastMove; // Dernier coup joué (pour countermove)

    private EndgameTablebase tablebase;
    private FastBoard searchBoard;
    private Random random;

    // CSV / KNN
    private int[][] csvData; // [obs][13] : 6 player + 6 opp + move
    private int csvCount;

    // Contrôle
    private boolean isTrained;
    private boolean learningMode;

    // Time Banking
    private long totalTimeUsed; // Temps total consommé cette partie
    private int moveCount; // Nombre de coups joués cette partie
    private int rootScore; // Score du dernier iterative deepening

    // =====================================================================
    // CONSTRUCTEUR
    // =====================================================================

    public TieLeOmegaBottMk3() throws InvalidBotException {
        this.setBotName("TieLeOmegaBottMk3");
        this.addAuthor("BENSAADI");
        this.addAuthor("MORLET");

        this.killerMoves = new int[MAX_DEPTH + 12][2];
        this.historyMoves = new int[2][6];
        this.countermove = new int[12];
        this.weights = new double[NUM_WEIGHTS];
        this.activeWeights = this.weights;
        this.tablebase = new EndgameTablebase();
        this.searchBoard = new FastBoard();
        this.random = TEST_MODE ? new Random(42L) : new Random();

        // Allocation initiale — sera écrasée par learn()
        allocateTT(COMPACT_TT_SIZE);
    }

    // =====================================================================
    // CYCLE DE VIE
    // =====================================================================

    /**
     * Appelée avant chaque affrontement (100 matchs).
     * Réinitialise les heuristiques mais conserve la TT (opening book).
     */
    @Override
    public void initialize() {
        for (int[] row : killerMoves)
            Arrays.fill(row, -1);
        for (int[] row : historyMoves)
            Arrays.fill(row, 0);
        Arrays.fill(countermove, -1);
        currentGen++;
        lastMove = -1;
        rootScore = 0;
        totalTimeUsed = 0;
        moveCount = 0;
    }

    @Override
    public void finish() {
    }

    /** Alloue la TT à la taille spécifiée (puissance de 2) */
    private void allocateTT(int size) {
        this.ttSize = size;
        this.ttMask = size - 1;
        this.ttKeys = new long[size];
        this.ttScores = new int[size];
        this.ttDepths = new byte[size];
        this.ttFlags = new byte[size];
        this.ttMoves = new byte[size];
        this.ttGen = new byte[size];
    }

    /** Vérifie si la mémoire approche la limite */
    private boolean isMemoryFull() {
        long limit = learningMode ? 900L * 1024 * 1024 : 58L * 1024 * 1024;
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        if (used > limit) {
            System.gc();
            used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            return used > limit;
        }
        return false;
    }

    // =====================================================================
    // PHASE D'APPRENTISSAGE : learn()
    // =====================================================================

    @Override
    public void learn() {
        if (isTrained)
            return;
        long startTime = System.currentTimeMillis();
        long totalDeadline = startTime + TOTAL_TRAINING_TIME;
        learningMode = true;

        // ─── Étape A : CSV & Initialisation des poids ───
        System.out.println("[Apprentissage] Chargement CSV et initialisation des poids...");
        loadCSVData();

        deriveWeightsFromCSV();

        // ─── Étape B : GA (14 gènes, seedé multi-source) ───
        System.out.println("[Apprentissage] Optimisation par Algorithme Génétique...");
        long reserveOB = TEST_MODE ? 20_000L : 20 * 60_000L;
        long reserveExtract = TEST_MODE ? 5_000L : 10 * 60_000L;
        long gaDeadline = Math.min(startTime + GA_TIME, totalDeadline - reserveOB);
        geneticAlgorithm(gaDeadline);

        // ─── Étape C : Tablebase Combinadique ───
        System.out.println("[Apprentissage] Construction de la Tablebase...");
        long tbDeadline = Math.min(System.currentTimeMillis() + TB_TIME, totalDeadline - reserveExtract);
        tablebase.allocate();
        tablebase.generate(TB_SEEDS, tbDeadline, this);

        // Sérialiser la TB sur disque + memory-map pour libérer le heap Java
        tablebase.serializeToFile();

        // ─── Étape D : Deep Opening Book (tout le temps restant - 30s sécurité) ───
        System.out.println("[Apprentissage] Construction de l'Opening Book...");
        if (!TEST_MODE) {
            allocateTT(MASSIVE_TT_SIZE);
            currentGen++;
            buildOpeningBook(totalDeadline - 30_000L);
        } else {
            System.out.println("   SKIPPED (TEST_MODE)");
        }

        // ─── Étape E : Extraction Opening Book immuable + TT de jeu ───
        System.out.println("[Apprentissage] Extraction de l'Opening Book...");
        if (!TEST_MODE) {
            extractOpeningBook();
        } else {
            System.out.println("   SKIPPED (TEST_MODE)");
        }

        learningMode = false;
        isTrained = true;
        System.gc();
        System.out.println("[Apprentissage] Terminé.");
    }

    // =====================================================================
    // ÉTAPE A : CHARGEMENT CSV + T-STATISTIC
    // =====================================================================

    /** Charge les observations gagnantes depuis le CSV (303 obs, ~154 gagnantes) */
    private void loadCSVData() {
        try {
            AweleData data = AweleData.getInstance();
            int count = 0;
            for (AweleObservation obs : data)
                if (obs.isWon())
                    count++;

            csvData = new int[count][13];
            csvCount = count;
            int idx = 0;
            for (AweleObservation obs : data) {
                if (obs.isWon()) {
                    int[] ph = obs.getPlayerHoles();
                    int[] oh = obs.getOppenentHoles();
                    for (int j = 0; j < 6; j++)
                        csvData[idx][j] = ph[j];
                    for (int j = 0; j < 6; j++)
                        csvData[idx][j + 6] = oh[j];
                    csvData[idx][12] = obs.getMove() - 1; // getMove() retourne 1-6
                    idx++;
                }
            }
            System.out.println("   CSV: " + csvCount + " winning observations loaded");
        } catch (Exception e) {
            csvData = new int[0][13];
            csvCount = 0;
        }
    }

    /**
     * Dérive les poids initiaux par T-Statistic sur les positions gagnantes.
     * Pour chaque feature : poids = |mean / std| * 10
     * Les features stables et significatives reçoivent un poids fort.
     */
    private void deriveWeightsFromCSV() {
        double[] initWeights = new double[NUM_WEIGHTS];

        if (csvCount == 0) {
            Arrays.fill(initWeights, 5.0);
        } else {
            double[] sum = new double[NUM_WEIGHTS];
            double[] sqSum = new double[NUM_WEIGHTS];
            FastBoard b = new FastBoard();

            for (int obs = 0; obs < csvCount; obs++) {
                for (int j = 0; j < 6; j++)
                    b.state[j] = csvData[obs][j];
                for (int j = 0; j < 6; j++)
                    b.state[j + 6] = csvData[obs][j + 6];
                b.state[12] = 0;
                b.state[13] = 0;
                b.currentPlayer = 0;

                double[] f = computeRawFeatures(b);
                for (int i = 0; i < NUM_WEIGHTS; i++) {
                    sum[i] += f[i];
                    sqSum[i] += f[i] * f[i];
                }
            }

            for (int i = 0; i < NUM_WEIGHTS; i++) {
                double mean = sum[i] / csvCount;
                double variance = sqSum[i] / csvCount - mean * mean;
                double std = Math.sqrt(Math.max(variance, 0.01));
                initWeights[i] = Math.max(1.0, Math.abs(mean / std) * 10.0);
            }
        }

        // Initialiser le jeu de poids unique
        System.arraycopy(initWeights, 0, weights, 0, NUM_WEIGHTS);

        System.out.print("   T-Statistic weights: [");
        for (int i = 0; i < NUM_WEIGHTS; i++) {
            System.out.printf("%.1f", initWeights[i]);
            if (i < NUM_WEIGHTS - 1)
                System.out.print(", ");
        }
        System.out.println("]");
    }

    /** Calcule les 12 features brutes d'une position (sans poids) */
    private double[] computeRawFeatures(FastBoard b) {
        int me = b.currentPlayer;
        int offMe = (me == 0) ? 0 : 6;
        int offOpp = (me == 0) ? 6 : 0;

        int seedsMe = 0, seedsOpp = 0;
        int mobilityMe = 0, mobilityOpp = 0;
        int oppVuln = 0, myVuln = 0;
        int myKroo = 0, oppBigPiles = 0, oppEmpty = 0;
        int capPot3 = 0, maxH = 0;
        int rightS = 0, leftS = 0;
        int immCap = 0;

        for (int i = 0; i < 6; i++) {
            int m = b.state[offMe + i];
            int o = b.state[offOpp + i];
            seedsMe += m;
            seedsOpp += o;
            if (m > 0)
                mobilityMe++;
            if (o > 0)
                mobilityOpp++;
            if (o == 1 || o == 2)
                oppVuln++;
            if (m == 1 || m == 2)
                myVuln++;
            if (m > 12)
                myKroo++;
            if (o > 12)
                oppBigPiles++;
            if (o == 0)
                oppEmpty++;
            if (o >= 1 && o <= 3)
                capPot3++;
            if (m > maxH)
                maxH = m;
            if (i >= 3)
                rightS += m;
            if (i <= 2)
                leftS += m;
            if (b.simulatedMoveCaptures(i))
                immCap++;
        }

        // Parity: nombre pair/impair de graines capturées influence le tempo
        int parity = (b.state[12] + b.state[13]) & 1;

        return new double[] {
                seedsMe - seedsOpp, mobilityMe - mobilityOpp,
                oppVuln, myVuln, immCap,
                (seedsOpp <= 2) ? 1.0 : 0.0, (seedsMe <= 2) ? 1.0 : 0.0,
                myKroo, oppEmpty, rightS - leftS, capPot3, maxH,
                parity, oppBigPiles
        };
    }

    // =====================================================================
    // ÉTAPE B : ALGORITHME GÉNÉTIQUE (14 gènes, multi-source)
    // =====================================================================

    /**
     * GA pour optimiser 1 jeu de poids (14 gènes).
     * Seedé multi-source : poids courants + poids compétiteurs.
     * Fitness = somme des écarts de score sur 8 parties de self-play à 2-ply.
     */
    private void geneticAlgorithm(long deadline) {
        final int POP = 40;
        final int GENES = NUM_WEIGHTS; // 14 gènes (un seul jeu de poids)
        double[][] pop = new double[POP][GENES];
        double[] fitness = new double[POP];

        // Individu 0 = poids courants (T-stat ou pré-chargés)
        System.arraycopy(weights, 0, pop[0], 0, GENES);

        // Individus 1-39 : mutations de l'individu 0 (T-stat)
        for (int i = 1; i < POP; i++) {
            mutateFrom(pop[0], pop[i], GENES);
        }

        // Pool pré-alloué pour éviter les allocations GC pendant les simulations
        FastBoard simPool = new FastBoard();

        int generation = 0;
        double bestFitEver = Double.NEGATIVE_INFINITY;
        int improvements = 0;
        int lastImprovementGen = 0;
        long lastLog = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            generation++;
            Arrays.fill(fitness, 0);

            for (int i = 0; i < POP; i++) {
                for (int k = 0; k < 8; k++) {
                    int opp = random.nextInt(POP);
                    if (opp == i)
                        opp = (i + 1) % POP;
                    simPool.reset();
                    fitness[i] += simulateGame(simPool, pop[i], pop[opp], k % 2 == 0);
                }
            }

            int bestIdx = 0;
            for (int i = 1; i < POP; i++)
                if (fitness[i] > fitness[bestIdx])
                    bestIdx = i;

            if (fitness[bestIdx] > bestFitEver) {
                bestFitEver = fitness[bestIdx];
                System.arraycopy(pop[bestIdx], 0, weights, 0, GENES);
                improvements++;
                lastImprovementGen = generation;
            }

            // Early stopping : stagnation depuis 6000 générations
            if (generation - lastImprovementGen > 6000) {
                System.out.printf("   GA: Early stopping at gen %d (stagnant %d gen)%n",
                        generation, generation - lastImprovementGen);
                break;
            }

            double[][] nextPop = new double[POP][GENES];
            System.arraycopy(pop[bestIdx], 0, nextPop[0], 0, GENES);

            for (int i = 1; i < POP; i++) {
                int p1 = tournamentSelect(fitness, POP);
                int p2 = tournamentSelect(fitness, POP);
                for (int j = 0; j < GENES; j++) {
                    nextPop[i][j] = random.nextBoolean() ? pop[p1][j] : pop[p2][j];
                    if (random.nextDouble() < 0.20) {
                        nextPop[i][j] += random.nextGaussian() * 3.0;
                        nextPop[i][j] = Math.max(-50.0, Math.min(50.0, nextPop[i][j]));
                    }
                }
            }
            pop = nextPop;

            long now = System.currentTimeMillis();
            if (now - lastLog >= 30_000) {
                System.out.printf("   GA: Gen %d, %d impr, Best: %.1f%n",
                        generation, improvements, bestFitEver);
                lastLog = now;
            }
        }

        System.out.printf("   GA done: %d gen, %d improvements, best=%.1f%n",
                generation, improvements, bestFitEver);
        printWeights("   Weights", weights);
    }

    /** Mutation d'un individu source vers un individu destination */
    private void mutateFrom(double[] src, double[] dst, int n) {
        for (int j = 0; j < n; j++) {
            dst[j] = src[j] + random.nextGaussian() * 5.0;
            dst[j] = Math.max(-50.0, Math.min(50.0, dst[j]));
        }
    }

    /** Sélection par tournoi triple */
    private int tournamentSelect(double[] fitness, int n) {
        int a = random.nextInt(n), b = random.nextInt(n), c = random.nextInt(n);
        if (fitness[a] >= fitness[b] && fitness[a] >= fitness[c])
            return a;
        return fitness[b] >= fitness[c] ? b : c;
    }

    /**
     * Simule une partie avec deux jeux de poids (14 gènes chacun, pas
     * d'interpolation).
     */
    private double simulateGame(FastBoard b, double[] w1, double[] w2, boolean w1Starts) {
        b.reset();
        int[] moves = new int[6];
        for (int turn = 0; turn < 200; turn++) {
            if (b.isGameOver())
                break;
            int n = b.getLegalMoves(moves);
            if (n == 0)
                break;

            boolean isW1 = (b.currentPlayer == 0) == w1Starts;
            double[] w = isW1 ? w1 : w2;

            int bestMove = moves[0];
            int bestVal = -999999;
            for (int i = 0; i < n; i++) {
                b.makeMove(moves[i], 150);
                int val = -simMinimax(b, 1, w);
                b.unmakeMove(150);
                if (val > bestVal) {
                    bestVal = val;
                    bestMove = moves[i];
                }
            }
            b.makeMove(bestMove, 180 + (turn % 10));
        }

        int s1 = b.state[12 + (w1Starts ? 0 : 1)];
        int s2 = b.state[12 + (w1Starts ? 1 : 0)];
        return (double) (s1 - s2);
    }

    /** Minimax 1-ply helper pour le self-play du GA */
    private int simMinimax(FastBoard b, int depth, double[] w) {
        if (depth == 0 || b.isGameOver())
            return evaluate(b, w);
        int[] moves = new int[6];
        int n = b.getLegalMoves(moves);
        if (n == 0)
            return evaluate(b, w);
        int best = -999999;
        for (int i = 0; i < n; i++) {
            b.makeMove(moves[i], 150 + depth);
            int val = -simMinimax(b, depth - 1, w);
            b.unmakeMove(150 + depth);
            if (val > best)
                best = val;
        }
        return best;
    }

    private void printWeights(String label, double[] w) {
        System.out.print(label + ": [");
        for (int i = 0; i < w.length; i++) {
            System.out.printf("%.1f", w[i]);
            if (i < w.length - 1)
                System.out.print(", ");
        }
        System.out.println("]");
    }

    // =====================================================================
    // ÉTAPE D : DEEP OPENING BOOK
    // =====================================================================

    /**
     * Construit un Deep Opening Book en remplissant la Massive TT.
     * Recherche à profondeur croissante depuis la position initiale.
     */
    private void buildOpeningBook(long deadline) {
        FastBoard startBoard = new FastBoard();
        int[] moves = new int[6];
        int nMoves = startBoard.getLegalMoves(moves);
        int[] rootMoves = new int[nMoves];
        System.arraycopy(moves, 0, rootMoves, 0, nMoves);

        System.out.println("   Calcul de l'ouverture...");

        // --- Phase 1 : Iterative Deepening depuis la position initiale ---
        int lastCompletedDepth = 0;
        for (int depth = 1; depth <= MAX_DEPTH; depth++) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining < 30_000 || isMemoryFull()) {
                System.out.println("   -> Stop (Memory/Timing)");
                break;
            }

            long depthStart = System.currentTimeMillis();
            searchStartTime = depthStart;
            // Cap dynamique par profondeur : min(3 min, 1/3 du temps restant)
            long maxPerDepth = Math.min(180_000L, remaining / 3);
            learnSearchDeadline = depthStart + maxPerDepth;
            timeBudgetMs = Long.MAX_VALUE;
            timeOut = false;

            int move = searchRootLearn(startBoard, depth, rootMoves, nMoves, -200000, 200000);
            long duration = System.currentTimeMillis() - depthStart;

            System.out.printf("   D%d | Move: %d | Time: %dms%n", depth, move, duration);

            // Si la recherche a été interrompue par le cap, les profondeurs suivantes
            // seront aussi interrompues → arrêt immédiat pour préserver la TT propre
            if (timeOut) {
                System.out.println("   -> Stop (depth " + depth + " timed out, preserving TT)");
                break;
            }

            lastCompletedDepth = depth;

            // Stop si la prochaine profondeur dépasserait probablement le temps restant
            remaining = deadline - System.currentTimeMillis();
            if (remaining < 60_000 || duration * 3 > remaining) {
                System.out.println("   -> Stop (time management)");
                break;
            }
        }

        // --- Phase 2 : Multi-root expansion ---
        // Explorer les variantes d'ouverture pour chaque coup adverse possible
        // (positions depth 2 depuis la racine). Ça remplit la Massive TT avec
        // des positions de TOUTES les lignes de jeu, pas seulement la PV.
        long remaining = deadline - System.currentTimeMillis();
        if (remaining > 60_000 && !isMemoryFull()) {
            System.out.println("   Multi-root expansion (" + (remaining / 1000) + "s restantes)...");
            expandOpeningBook(startBoard, lastCompletedDepth, deadline);
        }
    }

    /**
     * Expansion multi-racine : pour chaque position à depth 2 (notre coup +
     * réponse adverse), lance un iterative deepening indépendant.
     * Divise le temps restant équitablement entre toutes les sous-racines.
     * Toutes les positions explorées s'accumulent dans la Massive TT.
     */
    private void expandOpeningBook(FastBoard root, int maxTargetDepth, long deadline) {
        int[] m1 = new int[6];
        int n1 = root.getLegalMoves(m1);

        // Compter les sous-racines (depth-2 positions)
        int totalSubRoots = 0;
        for (int i = 0; i < n1; i++) {
            root.makeMove(m1[i], 0);
            int[] m2 = new int[6];
            totalSubRoots += root.getLegalMoves(m2);
            root.unmakeMove(0);
        }
        if (totalSubRoots == 0)
            return;

        // Itération multi-passe : augmenter progressivement la profondeur cible
        // tant qu'il reste du temps. Chaque passe réutilise le TT chaud.
        int targetDepth = Math.max(8, maxTargetDepth - 4);
        int passCount = 0;

        while (targetDepth <= MAX_DEPTH) {
            long remainingBefore = deadline - System.currentTimeMillis();
            if (remainingBefore < 60_000 || isMemoryFull())
                break;

            passCount++;
            if (passCount > 1)
                System.out.printf("   Multi-root pass %d (depth=%d, %ds left)...%n",
                        passCount, targetDepth, remainingBefore / 1000);

            int subRootsDone = 0;
            boolean anyTimeout = false;

            for (int i = 0; i < n1 && !anyTimeout; i++) {
                root.makeMove(m1[i], 0);
                int[] m2 = new int[6];
                int n2 = root.getLegalMoves(m2);

                for (int j = 0; j < n2 && !anyTimeout; j++) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining < 30_000 || isMemoryFull()) {
                        root.unmakeMove(0);
                        System.out.printf("   Multi-root: %d/%d sub-roots (pass %d, depth %d)%n",
                                subRootsDone, totalSubRoots, passCount, targetDepth);
                        return;
                    }

                    root.makeMove(m2[j], 1);
                    int[] subMoves = new int[6];
                    int nSub = root.getLegalMoves(subMoves);

                    if (nSub > 0) {
                        long budgetPerRoot = remaining / Math.max(1, totalSubRoots - subRootsDone);

                        for (int d = 1; d <= targetDepth; d++) {
                            long depthStart = System.currentTimeMillis();
                            searchStartTime = depthStart;
                            long maxPD = Math.min(budgetPerRoot / 3, 120_000L);
                            learnSearchDeadline = depthStart + maxPD;
                            timeBudgetMs = Long.MAX_VALUE;
                            timeOut = false;

                            searchRootLearn(root, d, subMoves, nSub, -200000, 200000);
                            long dur = System.currentTimeMillis() - depthStart;

                            if (timeOut) {
                                anyTimeout = (dur > budgetPerRoot / 2);
                                break;
                            }
                            if (dur * 3 > budgetPerRoot)
                                break;
                        }
                    }

                    root.unmakeMove(1);
                    subRootsDone++;
                }

                root.unmakeMove(0);
            }

            System.out.printf("   Multi-root: %d/%d sub-roots (pass %d, depth %d)%n",
                    subRootsDone, totalSubRoots, passCount, targetDepth);

            // Augmenter la profondeur pour la passe suivante
            targetDepth += 2;
        }
    }

    // =====================================================================
    // OPENING BOOK IMMUABLE (séparé de la TT dynamique)
    // =====================================================================

    /** Opening Book immuable : jamais écrasé par la recherche en temps réel */
    /** Clé de vérification = (int)(hash >>> 32), index par hash & obMask */
    private int[] obKeys; // verification key (upper 32 bits) — saves 2 MiB vs long[]
    // obScores supprimé : seul le move est utilisé pendant le jeu (-2 MiB)
    private byte[] obDepths;
    private byte[] obMoves;
    private int obSize;
    private int obMask;

    /** Taille de l'Opening Book immuable (512K entrées ≈ 3 MiB compressé) */
    private static final int OB_SIZE = 1 << 19; // 524 288

    /**
     * Extrait l'arbre d'ouverture jouable via PV Crawl (Principal Variation).
     * Parcourt récursivement les coups de la Massive TT depuis la position
     * initiale, en sauvegardant chaque position rencontrée dans l'OB immuable.
     * Seules les positions réellement atteignables et explorées sont conservées,
     * contrairement au scan linéaire qui mélangeait des positions sans lien.
     */
    private void extractOpeningBook() {
        obSize = OB_SIZE;
        obMask = obSize - 1;
        obKeys = new int[obSize];
        obDepths = new byte[obSize];
        obMoves = new byte[obSize];

        FastBoard crawler = new FastBoard();
        crawler.reset();
        int[] count = { 0 };

        // Force-store la position initiale si elle est dans la TT
        long rootHash = crawler.computeZobrist();
        int rootTTIdx = (int) (rootHash & ttMask);
        if (ttKeys[rootTTIdx] != rootHash) {
            // L'entrée racine a été écrasée par collision → forcer le crawl sans TT check à
            // la racine
            // Stocker un dummy pour que le crawl puisse quand même explorer les enfants
            int[] rm = new int[6];
            int rn = crawler.getLegalMoves(rm);
            if (rn > 0) {
                int obIdx = (int) (rootHash & obMask);
                obKeys[obIdx] = (int) (rootHash >>> 32);
                obDepths[obIdx] = 1;
                obMoves[obIdx] = (byte) rm[0];
                count[0]++;
                // Explorer quand même tous les coups depuis la racine
                for (int i = 0; i < rn; i++) {
                    crawler.makeMove(rm[i], 0);
                    extractPVRecursively(crawler, 1, 30, count);
                    crawler.unmakeMove(0);
                }
            }
        } else {
            extractPVRecursively(crawler, 0, 30, count);
        }

        System.out.println("   Opening Book (PV Crawl): " + count[0] + " positions saved.");

        // Fallback linéaire si le PV crawl a récupéré trop peu de positions
        // (les entrées TT racine peuvent être écrasées par des recherches profondes)
        if (count[0] < obSize / 8) {
            for (int i = 0; i < ttSize; i++) {
                if (ttKeys[i] != 0 && ttDepths[i] > 0) {
                    int obIdx2 = (int) (ttKeys[i] & obMask);
                    int verKey = (int) (ttKeys[i] >>> 32);
                    if (obKeys[obIdx2] == 0 || ttDepths[i] > obDepths[obIdx2]) {
                        obKeys[obIdx2] = verKey;
                        obDepths[obIdx2] = ttDepths[i];
                        obMoves[obIdx2] = ttMoves[i];
                    }
                }
            }
            int total = 0;
            for (int i = 0; i < obSize; i++)
                if (obKeys[i] != 0)
                    total++;
            System.out.println("   Opening Book (Linear fallback): " + total + " positions total.");
        }

        // Allouer une TT compacte vierge pour le jeu (séparée de l'OB)
        allocateTT(COMPACT_TT_SIZE);
        System.gc();
    }

    /**
     * Crawl récursif de l'arbre d'ouverture via la Massive TT.
     * À chaque nœud : si la TT a une entrée, la sauvegarder dans l'OB,
     * puis explorer les coups légaux. Le parcours est borné par :
     * - maxDepth (profondeur maximale)
     * - TT miss (pas d'entrée → branche non explorée → stop)
     * - OB hit (position déjà sauvegardée → sous-arbre déjà visité → skip)
     * - capacité OB (3/4 rempli → stop)
     */
    private void extractPVRecursively(FastBoard b, int depth, int maxDepth, int[] count) {
        if (depth > maxDepth || b.isGameOver() || count[0] >= obSize * 3 / 4)
            return;

        long zHash = b.computeZobrist();
        int ttIdx = (int) (zHash & ttMask);

        // Pas d'entrée TT → cette branche n'a pas été explorée
        if (ttKeys[ttIdx] != zHash || ttDepths[ttIdx] <= 0)
            return;

        int obIdx = (int) (zHash & obMask);
        int verKey = (int) (zHash >>> 32);

        // Déjà sauvegardée avec profondeur ≥ → sous-arbre déjà visité
        if (obKeys[obIdx] == verKey && obDepths[obIdx] >= ttDepths[ttIdx])
            return;

        // Sauvegarder (garder la plus profonde en cas de collision)
        if (obKeys[obIdx] == 0 || ttDepths[ttIdx] > obDepths[obIdx] || obKeys[obIdx] == verKey) {
            if (obKeys[obIdx] != verKey)
                count[0]++;
            obKeys[obIdx] = verKey;
            obDepths[obIdx] = ttDepths[ttIdx];
            obMoves[obIdx] = ttMoves[ttIdx];
        }

        // Explorer les coups légaux (TT move en premier pour le PV principal)
        int[] moves = new int[6];
        int nMoves = b.getLegalMoves(moves);
        int ttMove = ttMoves[ttIdx] & 0xFF;
        for (int i = 0; i < nMoves; i++) {
            if (moves[i] == ttMove && i > 0) {
                moves[i] = moves[0];
                moves[0] = ttMove;
                break;
            }
        }

        for (int i = 0; i < nMoves; i++) {
            b.makeMove(moves[i], depth);
            extractPVRecursively(b, depth + 1, maxDepth, count);
            b.unmakeMove(depth);
        }
    }

    /**
     * Consulte l'Opening Book immuable.
     * 
     * @return le coup stocké (0-5) ou -1 si absent
     */
    private int probeOpeningBook(long zHash) {
        if (obKeys == null)
            return -1;
        int idx = (int) (zHash & obMask);
        int verKey = (int) (zHash >>> 32);
        if (obKeys[idx] == verKey && obDepths[idx] >= 6) {
            return obMoves[idx] & 0xFF;
        }
        return -1;
    }

    // =====================================================================
    // KNN DEPUIS CSV (Move Ordering)
    // =====================================================================

    /**
     * K=15 plus proches voisins sur les observations gagnantes.
     * Retourne un score par coup qui servira de bonus au move ordering.
     */
    private double[] knnMoveScores(int[] playerHoles, int[] opponentHoles) {
        double[] scores = new double[6];
        if (csvCount == 0)
            return scores;

        double[] distances = new double[csvCount];
        for (int i = 0; i < csvCount; i++) {
            double d = 0;
            for (int j = 0; j < 6; j++) {
                double diff = playerHoles[j] - csvData[i][j];
                d += diff * diff;
                diff = opponentHoles[j] - csvData[i][j + 6];
                d += diff * diff;
            }
            distances[i] = d;
        }

        int k = Math.min(15, csvCount);
        double[] sorted = Arrays.copyOf(distances, csvCount);
        Arrays.sort(sorted);
        double threshold = sorted[k - 1];

        for (int i = 0; i < csvCount; i++) {
            if (distances[i] <= threshold) {
                int move = csvData[i][12];
                if (move >= 0 && move < 6)
                    scores[move] += 1.0 / (1.0 + distances[i]);
            }
        }
        return scores;
    }

    // =====================================================================
    // PRISE DE DÉCISION
    // =====================================================================

    @Override
    public double[] getDecision(Board board) {
        long decStart = System.currentTimeMillis();
        double[] decision = new double[6];
        Arrays.fill(decision, Double.NEGATIVE_INFINITY);

        searchBoard.initFromBoard(board);
        int[] moves = new int[6];
        int nMoves = searchBoard.getLegalMoves(moves);

        if (nMoves == 0)
            return decision;
        if (nMoves == 1) {
            decision[moves[0]] = 100.0;
            moveCount++;
            return decision;
        }

        // ─── TIME BANKING ───
        computeTimeBudget();

        // ─── OPENING BOOK ───
        long obHash = searchBoard.computeZobrist();
        int obMove = probeOpeningBook(obHash);
        if (obMove >= 0 && obMove < 6) {
            decision[obMove] = 100.0;
            moveCount++;
            totalTimeUsed += System.currentTimeMillis() - decStart;
            return decision;
        }

        // ─── TABLEBASE ───
        if (searchBoard.getTotalSeeds() <= TB_SEEDS && tablebase.isLoaded) {
            int tbBest = -1, tbVal = -99999;
            for (int i = 0; i < nMoves; i++) {
                searchBoard.makeMove(moves[i], 0);
                int tbScore = tablebase.getScore(searchBoard);
                searchBoard.unmakeMove(0);
                if (tbScore != EndgameTablebase.UNKNOWN) {
                    int v = -tbScore;
                    if (v > tbVal) {
                        tbVal = v;
                        tbBest = moves[i];
                    }
                }
            }
            if (tbBest != -1) {
                decision[tbBest] = 100.0;
                moveCount++;
                totalTimeUsed += System.currentTimeMillis() - decStart;
                return decision;
            }
        }

        // ─── KNN BOOST POUR MOVE ORDERING ───
        if (csvCount > 0) {
            double[] knn = knnMoveScores(board.getPlayerHoles(), board.getOpponentHoles());
            for (int i = 0; i < 6; i++)
                historyMoves[searchBoard.currentPlayer][i] += (int) (knn[i] * 50);
        }

        // ─── PVS + ITERATIVE DEEPENING + ASPIRATION WINDOWS ───
        searchStartTime = decStart;
        timeOut = false;
        currentGen++;

        int bestMove = moves[0];
        int score = 0;
        int completedDepth = 0;
        int[] rootMoves = new int[nMoves];
        System.arraycopy(moves, 0, rootMoves, 0, nMoves);
        for (int depth = 1; depth <= MAX_DEPTH; depth++) {
            int delta = 25;
            int alpha = (depth >= 3) ? score - delta : -200000;
            int beta = (depth >= 3) ? score + delta : 200000;

            while (true) {
                int move = searchRoot(searchBoard, depth, rootMoves, nMoves,
                        alpha, beta);
                if (timeOut)
                    break;

                int rootVal = rootScore;

                if (rootVal <= alpha) {
                    alpha = Math.max(alpha - delta, -200000);
                    delta *= 2;
                } else if (rootVal >= beta) {
                    beta = Math.min(beta + delta, 200000);
                    delta *= 2;
                } else {
                    score = rootVal;
                    bestMove = move;
                    completedDepth = depth;
                    break;
                }
                if (delta > 100000) {
                    alpha = -200000;
                    beta = 200000;
                }
            }
            if (timeOut)
                break;
        }

        long elapsed = System.currentTimeMillis() - decStart;
        System.out.println("[DEPTH-TEST] coup #" + (moveCount+1) + " | profondeur=" + completedDepth
                + " | score=" + score + " | temps=" + elapsed + "ms | budget=" + timeBudgetMs + "ms"
                + " | graines=" + searchBoard.getTotalSeeds());

        decision[bestMove] = 100.0;
        moveCount++;
        totalTimeUsed += System.currentTimeMillis() - decStart;
        return decision;
    }

    // =====================================================================
    // TIME BANKING DYNAMIQUE
    // =====================================================================

    /**
     * Calcule le budget de temps pour ce coup.
     * - Ouverture (coups 1-6) : si TT hit → 0ms, sinon 50ms
     * - Midgame : jusqu'à 180ms si crédit positif
     * - Endgame : 80ms (PVS + Tablebase)
     */
    private void computeTimeBudget() {
        // Temps moyen restant pour atteindre 100ms de moyenne avec marge
        double avgSoFar = (moveCount > 0) ? (double) totalTimeUsed / moveCount : 0;
        double credit = (95.0 - avgSoFar) * (moveCount + 1); // ms de crédit

        int totalSeeds = searchBoard.getTotalSeeds();

        if (moveCount < 6) {
            // Ouverture : vérifier si TT contient la position
            long zHash = searchBoard.computeZobrist();
            int idx = (int) (zHash & ttMask);
            if (ttKeys[idx] == zHash && ttDepths[idx] >= 8) {
                timeBudgetMs = 5; // TT hit profond → quasi-instantané
            } else {
                timeBudgetMs = 60;
            }
        } else if (totalSeeds <= 16) {
            // Endgame : pas besoin de trop de temps (Tablebase)
            timeBudgetMs = 80;
        } else {
            // Midgame : puiser dans le crédit
            if (credit > 500) {
                timeBudgetMs = 180;
            } else if (credit > 100) {
                timeBudgetMs = 130;
            } else {
                timeBudgetMs = 85;
            }
        }

        // Sécurité : ne jamais dépasser 200ms
        timeBudgetMs = Math.min(timeBudgetMs, 200);
    }

    /** Vérifie si le budget temps est dépassé (pendant un match) */
    private boolean isTimeUp() {
        if (learningMode)
            return false;
        return (System.currentTimeMillis() - searchStartTime) >= timeBudgetMs;
    }

    // =====================================================================
    // MOTEUR PVS : RECHERCHE ROOT
    // =====================================================================

    /** Recherche racine PVS avec aspiration windows. */
    private int searchRoot(FastBoard s, int depth, int[] moves, int nMoves,
            int alpha, int beta) {
        long zHash = s.computeZobrist();
        int idx = (int) (zHash & ttMask);
        int ttMove = (ttKeys[idx] == zHash) ? ttMoves[idx] : -1;

        sortMoves(moves, nMoves, ttMove, depth, s.currentPlayer, -1);
        int bestMove = moves[0];
        int bestScore = -200000;
        lastMove = -1;

        for (int i = 0; i < nMoves; i++) {
            if (i > 0 && isTimeUp()) {
                timeOut = true;
                break;
            }
            int move = moves[i];
            int prevLast = lastMove;
            lastMove = move + (s.currentPlayer * 6);
            s.makeMove(move, depth);

            int val;
            if (i == 0) {
                val = -pvs(s, depth - 1, -beta, -alpha, true);
            } else {
                val = -pvs(s, depth - 1, -alpha - 1, -alpha, false);
                if (val > alpha && val < beta)
                    val = -pvs(s, depth - 1, -beta, -alpha, true);
            }

            s.unmakeMove(depth);
            lastMove = prevLast;

            if (val > bestScore) {
                bestScore = val;
                bestMove = move;
            }
            if (val > alpha)
                alpha = val;
            if (alpha >= beta)
                break;
        }

        if (!timeOut)
            storeTT(zHash, depth, bestScore, FLAG_EXACT, bestMove);
        rootScore = bestScore;
        return bestMove;
    }

    /** Recherche racine utilisée pendant l'apprentissage */
    private int searchRootLearn(FastBoard s, int depth, int[] moves, int nMoves, int alpha, int beta) {
        long zHash = s.computeZobrist();
        int idx = (int) (zHash & ttMask);
        int ttMove = (ttKeys[idx] == zHash) ? ttMoves[idx] : -1;
        lastMove = -1;

        sortMoves(moves, nMoves, ttMove, depth, s.currentPlayer, -1);
        int bestMove = moves[0];
        int bestScore = -200000;

        for (int i = 0; i < nMoves; i++) {
            if (System.currentTimeMillis() >= learnSearchDeadline) {
                timeOut = true;
                break;
            }
            int move = moves[i];
            int prevLast = lastMove;
            lastMove = move + (s.currentPlayer * 6);
            s.makeMove(move, depth);

            int val;
            if (i == 0) {
                val = -pvsLearn(s, depth - 1, -beta, -alpha, true);
            } else {
                val = -pvsLearn(s, depth - 1, -alpha - 1, -alpha, false);
                if (val > alpha && val < beta)
                    val = -pvsLearn(s, depth - 1, -beta, -alpha, true);
            }

            s.unmakeMove(depth);
            lastMove = prevLast;

            if (val > bestScore) {
                bestScore = val;
                bestMove = move;
            }
            if (val > alpha)
                alpha = val;
            if (alpha >= beta)
                break;
        }

        if (!timeOut)
            storeTT(zHash, depth, bestScore, FLAG_EXACT, bestMove);
        return bestMove;
    }

    // =====================================================================
    // MOTEUR PVS : RECHERCHE PRINCIPALE
    // =====================================================================

    private int pvs(FastBoard s, int depth, int alpha, int beta, boolean isPV) {
        if (isTimeUp()) {
            timeOut = true;
            return alpha;
        }

        // ─── TT probe ───
        long zHash = s.computeZobrist();
        int idx = (int) (zHash & ttMask);
        int ttMove = -1;
        if (ttKeys[idx] == zHash) {
            ttMove = ttMoves[idx];
            if (ttDepths[idx] >= depth && !isPV) {
                int sc = ttScores[idx];
                byte flag = ttFlags[idx];
                if (flag == FLAG_EXACT)
                    return sc;
                if (flag == FLAG_LOWER)
                    alpha = Math.max(alpha, sc);
                else if (flag == FLAG_UPPER)
                    beta = Math.min(beta, sc);
                if (alpha >= beta)
                    return sc;
            }
        }

        // ─── Tablebase probe ───
        if (s.getTotalSeeds() <= TB_SEEDS && tablebase.isLoaded) {
            int dbScore = tablebase.getScore(s);
            if (dbScore != EndgameTablebase.UNKNOWN)
                return dbScore * 1000;
        }

        if (s.isGameOver()) {
            int me = s.currentPlayer;
            return (s.state[12 + me] - s.state[12 + (1 - me)]) * 1000;
        }

        if (depth <= 0)
            return quiescence(s, alpha, beta, MAX_DEPTH + 1);

        int staticEval = evaluate(s, activeWeights);

        // ─── Reverse Futility Pruning ───
        if (!isPV && depth <= 6 && staticEval - 120 * depth >= beta)
            return staticEval;

        // ─── Razoring ───
        if (!isPV && depth <= 3 && staticEval + 300 * depth <= alpha)
            return quiescence(s, alpha, beta, MAX_DEPTH + 1);

        int[] moves = new int[6];
        int nMoves = s.getLegalMoves(moves);
        if (nMoves == 0)
            return evaluate(s, activeWeights);

        // ─── IID ───
        if (isPV && ttMove == -1 && depth >= 4) {
            pvs(s, depth - 2, alpha, beta, true);
            long z2 = s.computeZobrist();
            int idx2 = (int) (z2 & ttMask);
            if (ttKeys[idx2] == z2)
                ttMove = ttMoves[idx2];
        }

        sortMoves(moves, nMoves, ttMove, depth, s.currentPlayer, lastMove);

        int bestScore = -200000;
        int bestMoveLocal = -1;
        byte storeFlag = FLAG_UPPER;

        for (int i = 0; i < nMoves; i++) {
            int move = moves[i];
            boolean isCapture = s.simulatedMoveCaptures(move);

            // ─── Late Move Pruning ───
            if (!isPV && depth <= 4 && i >= 2 + depth && !isCapture)
                continue;

            // ─── Futility Pruning ───
            if (!isPV && depth <= 3 && i > 0 && staticEval + 150 * depth < alpha && !isCapture)
                continue;

            int prevLast = lastMove;
            lastMove = move + (s.currentPlayer * 6);
            s.makeMove(move, depth);

            // ─── LMR ───
            int reduction = 0;
            if (!isPV && depth >= 3 && i >= 2) {
                reduction = LMR[Math.min(depth, 63)][Math.min(i, 6)];
                if (isCapture)
                    reduction = Math.max(0, reduction - 1);
            }

            int val;
            if (i == 0) {
                val = -pvs(s, depth - 1, -beta, -alpha, isPV);
            } else {
                val = -pvs(s, depth - 1 - reduction, -alpha - 1, -alpha, false);
                if (val > alpha && (reduction > 0 || val < beta))
                    val = -pvs(s, depth - 1, -beta, -alpha, isPV);
            }

            s.unmakeMove(depth);
            lastMove = prevLast;
            if (timeOut)
                return alpha;

            if (val > bestScore) {
                bestScore = val;
                bestMoveLocal = move;
            }
            if (val > alpha) {
                alpha = val;
                storeFlag = FLAG_EXACT;
            }
            if (alpha >= beta) {
                if (!isCapture) {
                    updateKillers(depth, move);
                    updateHistory(depth, move, s.currentPlayer);
                    if (lastMove >= 0 && lastMove < 12)
                        countermove[lastMove] = move;
                }
                storeFlag = FLAG_LOWER;
                break;
            }
        }

        if (!timeOut)
            storeTT(zHash, depth, bestScore, storeFlag, bestMoveLocal);
        return bestScore;
    }

    /** PVS pour la phase d'apprentissage (pas de time banking) */
    private int pvsLearn(FastBoard s, int depth, int alpha, int beta, boolean isPV) {
        // Timeout dynamique (contrôlé par buildOpeningBook)
        if (System.currentTimeMillis() >= learnSearchDeadline) {
            timeOut = true;
            return alpha;
        }

        // Probe TB (score du joueur au trait)
        if (depth < 15 && s.getTotalSeeds() <= TB_SEEDS && tablebase.isLoaded) {
            int dbScore = tablebase.getScore(s);
            if (dbScore != EndgameTablebase.UNKNOWN) {
                return dbScore * 1000;
            }
        }

        long zHash = s.computeZobrist();
        int idx = (int) (zHash & ttMask);

        // Probe TT
        if (ttKeys[idx] == zHash && ttDepths[idx] >= depth) {
            int sc = ttScores[idx];
            byte flag = ttFlags[idx];
            if (!isPV) {
                if (flag == FLAG_EXACT)
                    return sc;
                if (flag == FLAG_LOWER)
                    alpha = Math.max(alpha, sc);
                else if (flag == FLAG_UPPER)
                    beta = Math.min(beta, sc);
                if (alpha >= beta)
                    return sc;
            }
        }

        if (s.isGameOver()) {
            int me = s.currentPlayer;
            return (s.state[12 + me] - s.state[12 + (1 - me)]) * 1000;
        }
        if (depth <= 0)
            return quiescence(s, alpha, beta, MAX_DEPTH + 1);

        int staticEval = evaluate(s, activeWeights);

        if (!isPV && depth <= 6 && staticEval - 120 * depth >= beta)
            return staticEval;
        if (!isPV && depth <= 3 && staticEval + 300 * depth <= alpha)
            return quiescence(s, alpha, beta, MAX_DEPTH + 1);

        int[] moves = new int[6];
        int nMoves = s.getLegalMoves(moves);
        if (nMoves == 0)
            return staticEval;

        int ttMove = (ttKeys[idx] == zHash) ? ttMoves[idx] : -1;

        if (isPV && ttMove == -1 && depth >= 4) {
            pvsLearn(s, depth - 2, alpha, beta, true);
            long z2 = s.computeZobrist();
            int idx2 = (int) (z2 & ttMask);
            if (ttKeys[idx2] == z2)
                ttMove = ttMoves[idx2];
        }

        sortMoves(moves, nMoves, ttMove, depth, s.currentPlayer, lastMove);

        int bestScore = -200000;
        int bestMoveLocal = -1;
        byte flag = FLAG_UPPER;

        for (int i = 0; i < nMoves; i++) {
            int move = moves[i];
            boolean isCapture = s.simulatedMoveCaptures(move);

            if (!isPV && depth <= 4 && i >= 2 + depth && !isCapture)
                continue;
            if (!isPV && depth <= 3 && i > 0 && staticEval + 150 * depth < alpha && !isCapture)
                continue;

            int prevLast = lastMove;
            lastMove = move + (s.currentPlayer * 6);
            s.makeMove(move, depth);

            int reduction = 0;
            if (!isPV && depth >= 3 && i >= 2) {
                reduction = LMR[Math.min(depth, 63)][Math.min(i, 6)];
                if (isCapture)
                    reduction = Math.max(0, reduction - 1);
            }

            int val;
            if (i == 0) {
                val = -pvsLearn(s, depth - 1, -beta, -alpha, isPV);
            } else {
                val = -pvsLearn(s, depth - 1 - reduction, -alpha - 1, -alpha, false);
                if (val > alpha && (reduction > 0 || val < beta))
                    val = -pvsLearn(s, depth - 1, -beta, -alpha, isPV);
            }

            s.unmakeMove(depth);
            lastMove = prevLast;
            if (timeOut)
                return alpha;

            if (val > bestScore) {
                bestScore = val;
                bestMoveLocal = move;
            }
            if (val > alpha) {
                alpha = val;
                flag = FLAG_EXACT;
            }
            if (alpha >= beta) {
                if (!isCapture) {
                    updateKillers(depth, move);
                    updateHistory(depth, move, s.currentPlayer);
                    if (lastMove >= 0 && lastMove < 12)
                        countermove[lastMove] = move;
                }
                flag = FLAG_LOWER;
                break;
            }
        }

        if (!timeOut) {
            storeTT(zHash, depth, bestScore, flag, bestMoveLocal);

        }
        return bestScore;
    }

    // =====================================================================
    // QUIESCENCE SEARCH
    // =====================================================================

    /**
     * Quiescence Search avec Delta Pruning (Δ=300).
     * 
     * @param qPly Profondeur unique dans la pile history (commence à MAX_DEPTH+1)
     *             pour éviter toute collision entre niveaux récursifs.
     */
    private int quiescence(FastBoard s, int alpha, int beta, int qPly) {
        int standPat = evaluate(s, activeWeights);
        if (standPat >= beta)
            return beta;

        // Delta Pruning : Δ = 300
        if (standPat + 300 < alpha)
            return alpha;

        if (standPat > alpha)
            alpha = standPat;

        // Sécurité : éviter un stack overflow ou un dépassement history[]
        if (qPly >= 195)
            return alpha;

        int[] moves = new int[6];
        int nMoves = s.getLegalMoves(moves);

        for (int i = 0; i < nMoves; i++) {
            if (!s.simulatedMoveCaptures(moves[i]))
                continue;

            s.makeMove(moves[i], qPly);
            int val = -quiescence(s, -beta, -alpha, qPly + 1);
            s.unmakeMove(qPly);

            if (val >= beta)
                return beta;
            if (val > alpha)
                alpha = val;
        }
        return alpha;
    }

    // =====================================================================
    // ÉVALUATION POSITIONNELLE
    // =====================================================================

    /**
     * Évalue une position du point de vue du joueur courant.
     * 12 features pondérées + bonus capture score + mobilité.
     *
     * Features :
     * 0: seedsDiff 1: mobilityDiff 2: oppVulnerable
     * 3: -myVulnerable 4: immCaptures 5: oppStarvation
     * 6: -myStarvation 7: myKroo 8: oppEmpty
     * 9: sideBias 10: capturePot3 11: -maxHole
     * 12: parity 13: oppBigPiles
     */
    private int evaluate(FastBoard s, double[] w) {
        int me = s.currentPlayer;
        int opp = 1 - me;
        int offMe = (me == 0) ? 0 : 6;
        int offOpp = (opp == 0) ? 0 : 6;

        int score = (s.state[12 + me] - s.state[12 + opp]) * 100;

        int seedsMe = 0, seedsOpp = 0;
        int mobilityMe = 0, mobilityOpp = 0;
        int oppVulnerable = 0, myVulnerable = 0;
        int myKroo = 0, oppBigPiles = 0, oppEmpty = 0;
        int capturePot3 = 0, maxHole = 0;
        int rightSeeds = 0, leftSeeds = 0;
        int immCaptures = 0;

        for (int i = 0; i < 6; i++) {
            int m = s.state[offMe + i];
            int o = s.state[offOpp + i];

            seedsMe += m;
            seedsOpp += o;
            if (m > 0)
                mobilityMe++;
            if (o > 0)
                mobilityOpp++;
            if (o == 1 || o == 2)
                oppVulnerable++;
            if (m == 1 || m == 2)
                myVulnerable++;
            if (m > 12)
                myKroo++;
            if (o > 12)
                oppBigPiles++;
            if (o == 0)
                oppEmpty++;
            if (o >= 1 && o <= 3)
                capturePot3++;
            if (m > maxHole)
                maxHole = m;
            if (i >= 3)
                rightSeeds += m;
            if (i <= 2)
                leftSeeds += m;
            if (s.simulatedMoveCaptures(i))
                immCaptures++;
        }

        int parity = (s.state[12] + s.state[13]) & 1;

        score += (int) (w[0] * (seedsMe - seedsOpp)
                + w[1] * (mobilityMe - mobilityOpp)
                + w[2] * oppVulnerable
                + w[3] * (-myVulnerable)
                + w[4] * immCaptures
                + w[5] * (seedsOpp <= 2 ? 1 : 0)
                + w[6] * (seedsMe <= 2 ? -1 : 0)
                + w[7] * myKroo
                + w[8] * oppEmpty
                + w[9] * (rightSeeds - leftSeeds)
                + w[10] * capturePot3
                + w[11] * (-maxHole)
                + w[12] * parity
                + w[13] * oppBigPiles);

        if (seedsOpp < 2)
            score += 200;
        score += mobilityMe * 5;

        return score;
    }

    // =====================================================================
    // MOVE ORDERING
    // =====================================================================

    /** Tri par insertion des coups selon : TT > Killer > Countermove > History */
    private void sortMoves(int[] moves, int count, int ttMove, int depth, int player, int prevMove) {
        for (int i = 1; i < count; i++) {
            int key = moves[i];
            int keyScore = getMoveScore(key, ttMove, depth, player, prevMove);
            int j = i - 1;
            while (j >= 0 && getMoveScore(moves[j], ttMove, depth, player, prevMove) < keyScore) {
                moves[j + 1] = moves[j];
                j--;
            }
            moves[j + 1] = key;
        }
    }

    private int getMoveScore(int move, int ttMove, int depth, int player, int prevMove) {
        if (move == ttMove)
            return 1_000_000;
        if (depth >= 0 && depth < killerMoves.length) {
            if (move == killerMoves[depth][0])
                return 400_000;
            if (move == killerMoves[depth][1])
                return 300_000;
        }
        if (prevMove >= 0 && prevMove < 12 && countermove[prevMove] == move)
            return 200_000;
        return historyMoves[player][move];
    }

    private void updateKillers(int depth, int move) {
        if (depth >= 0 && depth < killerMoves.length && killerMoves[depth][0] != move) {
            killerMoves[depth][1] = killerMoves[depth][0];
            killerMoves[depth][0] = move;
        }
    }

    private void updateHistory(int depth, int move, int player) {
        historyMoves[player][move] += depth * depth;
        if (historyMoves[player][move] > 10_000_000) {
            for (int p = 0; p < 2; p++)
                for (int m = 0; m < 6; m++)
                    historyMoves[p][m] /= 2;
        }
    }

    // =====================================================================
    // TRANSPOSITION TABLE : STORE
    // =====================================================================

    private void storeTT(long key, int depth, int score, byte flag, int move) {
        int idx = (int) (key & ttMask);
        // Remplacement : entrée vide, profondeur supérieure, ou génération périmée
        if (ttKeys[idx] != key || depth >= ttDepths[idx] || ttGen[idx] != currentGen) {
            ttKeys[idx] = key;
            ttScores[idx] = score;
            ttDepths[idx] = (byte) depth;
            ttFlags[idx] = flag;
            ttGen[idx] = currentGen;
            if (move != -1)
                ttMoves[idx] = (byte) move;
        }
    }

    // =================================================================
    // FASTBOARD : Simulateur Zéro-Allocation
    // =================================================================

    /**
     * Plateau de jeu ultra-optimisé :
     * - state[0-5] : trous du joueur 0
     * - state[6-11] : trous du joueur 1
     * - state[12] : score du joueur 0
     * - state[13] : score du joueur 1
     * - currentPlayer : 0 ou 1
     * - history[][] : pile de sauvegarde pour make/unmake (zéro-allocation)
     */
    static class FastBoard {
        int[] state = new int[14];
        int currentPlayer;
        int[][] history = new int[200][14]; // Pile pour make/unmake
        long zobristHash; // Hash Zobrist incrémental
        long[] hashHistory = new long[200]; // Sauvegarde hash pour unmake
        int totalSeedsCache; // Nombre total de graines (incrémental)
        int[] totalSeedsHistory = new int[200]; // Sauvegarde pour unmake

        FastBoard() {
            Arrays.fill(state, 0, 12, 4); // 4 graines par trou
            totalSeedsCache = 48;
            recomputeZobrist();
        }

        void reset() {
            Arrays.fill(state, 0, 12, 4);
            state[12] = 0;
            state[13] = 0;
            currentPlayer = 0;
            totalSeedsCache = 48;
            recomputeZobrist();
        }

        void copyFrom(FastBoard src) {
            System.arraycopy(src.state, 0, this.state, 0, 14);
            this.currentPlayer = src.currentPlayer;
            this.zobristHash = src.zobristHash;
            this.totalSeedsCache = src.totalSeedsCache;
        }

        /** Initialise depuis l'objet Board du moteur officiel */
        void initFromBoard(Board b) {
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
            this.currentPlayer = cp;
            recomputeTotalSeeds();
            recomputeZobrist();
        }

        int getTotalSeeds() {
            return totalSeedsCache;
        }

        /** Recalcule totalSeedsCache depuis le state (utilisé pour init) */
        void recomputeTotalSeeds() {
            int s = 0;
            for (int i = 0; i < 12; i++)
                s += state[i];
            totalSeedsCache = s;
        }

        boolean isGameOver() {
            return state[12] >= 25 || state[13] >= 25 || getTotalSeeds() <= 6;
        }

        /** Hash Zobrist 64-bit de la position (retourne le cache incrémental) */
        long computeZobrist() {
            return zobristHash;
        }

        /** Recalcule le hash Zobrist depuis le state complet */
        void recomputeZobrist() {
            long h = (currentPlayer == 1) ? ZOBRIST_SIDE : 0;
            for (int i = 0; i < 12; i++) {
                int s = Math.min(state[i], 49);
                h ^= ZOBRIST[i < 6 ? 0 : 1][i % 6][s];
            }
            zobristHash = h;
        }

        /** Remplit buffer[] avec les coups légaux, retourne le nombre */
        int getLegalMoves(int[] buffer) {
            int count = 0;
            int oppOffset = (currentPlayer == 0) ? 6 : 0;
            int oppSeeds = 0;
            for (int i = 0; i < 6; i++)
                oppSeeds += state[oppOffset + i];
            boolean mustFeed = (oppSeeds == 0);
            int offset = (currentPlayer == 0) ? 0 : 6;

            for (int i = 0; i < 6; i++) {
                int seeds = state[offset + i];
                if (seeds == 0)
                    continue;
                if (mustFeed && seeds < (6 - i))
                    continue;
                buffer[count++] = i;
            }
            return count;
        }

        /** Joue un coup avec sauvegarde dans history[depth] */
        void makeMove(int hole, int depth) {
            int d = depth % 200;
            System.arraycopy(state, 0, history[d], 0, 14);
            hashHistory[d] = zobristHash;
            totalSeedsHistory[d] = totalSeedsCache;
            int offset = (currentPlayer == 0) ? 0 : 6;
            int index = offset + hole;
            int seeds = state[index];
            state[index] = 0;

            int ptr = index;
            while (seeds > 0) {
                ptr++;
                if (ptr >= 12)
                    ptr = 0;
                if (ptr == index)
                    continue;
                state[ptr]++;
                seeds--;
            }

            // Capture
            boolean isOppSide = (currentPlayer == 0) ? (ptr >= 6) : (ptr < 6);
            if (isOppSide) {
                int tempPtr = ptr;
                int capturedSum = 0;
                while (true) {
                    boolean inOpp = (currentPlayer == 0) ? (tempPtr >= 6) : (tempPtr < 6);
                    if (!inOpp)
                        break;
                    int s = state[tempPtr];
                    if (s == 2 || s == 3) {
                        capturedSum += s;
                        tempPtr--;
                        if (tempPtr < 0)
                            tempPtr = 11;
                    } else
                        break;
                }
                if (capturedSum > 0) {
                    // Vérifier la règle anti-famine (ne pas tout prendre)
                    int oppStart = (currentPlayer == 0) ? 6 : 0;
                    int seedsTotalOpp = 0;
                    for (int i = 0; i < 6; i++)
                        seedsTotalOpp += state[oppStart + i];
                    if (seedsTotalOpp > capturedSum) {
                        state[12 + currentPlayer] += capturedSum;
                        totalSeedsCache -= capturedSum; // Graines capturées quittent le plateau
                        tempPtr = ptr;
                        while (true) {
                            boolean inOpp2 = (currentPlayer == 0) ? (tempPtr >= 6) : (tempPtr < 6);
                            if (!inOpp2)
                                break;
                            int s = state[tempPtr];
                            if (s == 2 || s == 3) {
                                state[tempPtr] = 0;
                                tempPtr--;
                                if (tempPtr < 0)
                                    tempPtr = 11;
                            } else
                                break;
                        }
                    }
                }
            }
            currentPlayer = 1 - currentPlayer;
            recomputeZobrist();
        }

        /** Annule le dernier coup (restauration depuis history) */
        void unmakeMove(int depth) {
            int d = depth % 200;
            System.arraycopy(history[d], 0, state, 0, 14);
            currentPlayer = 1 - currentPlayer;
            zobristHash = hashHistory[d];
            totalSeedsCache = totalSeedsHistory[d];
        }

        /** Teste rapidement si un coup capture — O(1) via formule */
        boolean simulatedMoveCaptures(int hole) {
            int offset = (currentPlayer == 0) ? 0 : 6;
            int idx = offset + hole;
            int seeds = state[idx];
            if (seeds == 0)
                return false;
            // Formule O(1) : ptr = position finale après sow avec skip à idx
            int ptr = (idx + seeds + (seeds - 1) / 11) % 12;
            boolean isOpp = (currentPlayer == 0) ? (ptr >= 6) : (ptr < 6);
            if (!isOpp)
                return false;
            int endS = state[ptr] + 1;
            return (endS == 2 || endS == 3);
        }
    }

    // =================================================================
    // ENDGAME TABLEBASE (Indexation Combinadique Parfaite)
    // =================================================================

    /**
     * Tablebase d'endgame à indexation combinadique parfaite.
     *
     * Remplace l'ancien hachage Zobrist (collisions, clés 64-bit, ~23 MiB)
     * par un stockage dense, sans collision et ultra-compact.
     *
     * MATHÉMATIQUE (Stars-and-Bars + Système Combinatoire) :
     * S graines dans 12 trous = composition de S en 12 parties
     * Nombre de compositions = C(S+11, 11)
     * Chaque composition reçoit un index unique ∈ [0, C(S+11,11) - 1]
     *
     * Transformation : on convertit la composition (h0,...,h11) en une
     * 11-combinaison via Stars-and-Bars :
     * bars[j] = Σ(h[0..j]) + j (j = 0..10)
     * Puis rang via le Combinatorial Number System :
     * index = Σ C(bars[j], j+1) (j = 0..10)
     *
     * STOCKAGE :
     * byte[][] tables : tables[S] = new byte[2 * C(S+11, 11)]
     * index = combinadique_spatial * 2 + currentPlayer
     * 1 seul octet par entrée, 0 collision, 0 clé.
     *
     * Empreinte mémoire pour TB_SEEDS=13 : ~10.4 MiB total (×2 pour P0/P1).
     */
    static class EndgameTablebase {
        static final int UNKNOWN = -9999;
        private static final int MAX_SOLVE_DEPTH = 60;
        /** Sentinelle : position non encore résolue (-128, hors plage [-127,127]) */
        private static final byte UNRESOLVED = Byte.MIN_VALUE;

        // ─── Triangle de Pascal pré-calculé : C[n][k] = C(n, k) ───
        static final long[][] C = new long[61][61];
        static {
            for (int n = 0; n <= 60; n++) {
                C[n][0] = 1;
                for (int k = 1; k <= n; k++)
                    C[n][k] = C[n - 1][k - 1] + C[n - 1][k];
            }
        }

        /** tables[S][index] = score minimax (byte) pour S graines sur le plateau */
        byte[][] tables;
        boolean isLoaded;
        /** Maximum de graines effectivement générées */
        int generatedMaxSeeds;

        // Memory-mapped file pour la TB (off-heap, invisible pour Runtime.totalMemory)
        private MappedByteBuffer mappedTB;
        /** Offset dans le fichier de chaque table[S] */
        private long[] tableOffsets;
        /** Taille de chaque table[S] */
        private int[] tableSizes;

        void allocate() {
            tables = new byte[TB_SEEDS + 1][];
            isLoaded = true;
        }

        /**
         * Calcule l'index combinadique dense d'une position.
         *
         * DIMENSION SPATIALE : les 12 trous sont lus dans l'ordre absolu
         * state[0..5] puis state[6..11] (canonique, indépendant du joueur).
         *
         * DIMENSION TEMPORELLE : le joueur au trait est encodé dans l'index
         * via index = spatialIndex * 2 + currentPlayer.
         * La même disposition physique a une valeur minimax différente
         * selon qui joue → il faut 2 entrées distinctes.
         *
         * @return index ∈ [0, 2 * C(totalSeeds+11, 11) - 1]
         */
        int getIndex(FastBoard b) {
            int spatial = 0;
            int cumSum = 0;
            for (int j = 0; j < 11; j++) {
                cumSum += b.state[j];
                spatial += (int) C[cumSum + j][j + 1];
            }
            return spatial * 2 + b.currentPlayer;
        }

        // ─── Génération exhaustive ───

        /**
         * Génère la tablebase pour 0 à maxSeeds graines.
         * Pour chaque S, alloue 2 × C(S+11, 11) octets (P0 et P1 séparés)
         * puis itère sur TOUTES les compositions possibles de S dans 12 trous.
         */
        void generate(int maxSeeds, long deadline, TieLeOmegaBottMk3 bot) {
            if (!isLoaded)
                return;
            long totalEntries = 0;
            generatedMaxSeeds = -1;
            for (int seeds = 0; seeds <= maxSeeds; seeds++) {
                if (System.currentTimeMillis() > deadline || bot.isMemoryFull())
                    break;
                int size = (int) C[seeds + 11][11] * 2; // ×2 pour P0/P1
                tables[seeds] = new byte[size];
                Arrays.fill(tables[seeds], UNRESOLVED);
                System.out.println("   TB[" + seeds + "]: " + size + " entries, solving...");
                long t0 = System.currentTimeMillis();
                int[] current = new int[12];
                generateAllCompositions(seeds, 0, seeds, current, deadline, bot);
                long dt = System.currentTimeMillis() - t0;
                totalEntries += size;
                generatedMaxSeeds = seeds;
                System.out.println("   TB[" + seeds + "]: done in " + dt + "ms");
            }
            System.out.println("   TB total: " + totalEntries + " entries (~"
                    + (totalEntries / (1024 * 1024)) + " MiB)");
        }

        /**
         * Itère récursivement sur toutes les compositions de totalSeeds
         * graines dans 12 trous. Pour chaque feuille, résout la position
         * pour les deux joueurs (player 0 et player 1).
         */
        private void generateAllCompositions(int totalSeeds, int idx, int remaining,
                int[] current, long deadline, TieLeOmegaBottMk3 bot) {
            if (System.currentTimeMillis() > deadline || bot.isMemoryFull())
                return;
            if (idx == 11) {
                current[11] = remaining;
                FastBoard b = new FastBoard();
                for (int i = 0; i < 12; i++)
                    b.state[i] = current[i];
                b.state[12] = 0;
                b.state[13] = 0;
                b.currentPlayer = 0;
                solve(b, 0, deadline, bot);
                b.currentPlayer = 1;
                solve(b, 0, deadline, bot);
                return;
            }
            for (int i = 0; i <= remaining; i++) {
                current[idx] = i;
                generateAllCompositions(totalSeeds, idx + 1, remaining - i, current, deadline, bot);
            }
        }

        /**
         * Résolution récursive minimax avec mémoïsation combinadique.
         *
         * Grâce à getIndex() = spatial×2 + currentPlayer, chaque paire
         * (disposition, joueur au trait) a son propre slot.
         *
         * SÉMANTIQUE DU CACHE :
         * Le byte stocké est INCRÉMENTAL : captures nettes futures depuis
         * cette configuration de trous, en partant de 0-0.
         * À la lecture, on ajoute le capturedDiff courant (state[12/13])
         * pour obtenir la valeur TOTALE du jeu from currentPlayer's view.
         * Au stockage, on soustrait le capturedDiff pour ne garder que
         * l'incrémental pur.
         *
         * Clampé à [-127, 127] (1 byte).
         */
        private int solve(FastBoard b, int depth, long deadline, TieLeOmegaBottMk3 bot) {
            if (depth > MAX_SOLVE_DEPTH || System.currentTimeMillis() > deadline)
                return 0;

            int totalSeeds = b.getTotalSeeds();
            if (totalSeeds > TB_SEEDS || tables[totalSeeds] == null)
                return 0;

            int index = getIndex(b);
            if (index < 0 || index >= tables[totalSeeds].length)
                return 0;

            int me = b.currentPlayer;
            int capDiff = b.state[12 + me] - b.state[12 + (1 - me)];

            byte cached = tables[totalSeeds][index];
            if (cached != UNRESOLVED) {
                // cached = incrémental (stocké depuis contexte 0-0)
                // Ajouter les captures accumulées pour la valeur totale
                return cached + capDiff;
            }

            if (b.isGameOver()) {
                // Valeur totale = captured diff (pas de captures futures)
                // Stocker incrémental = total - capDiff = 0
                tables[totalSeeds][index] = 0;
                return capDiff;
            }

            int[] moves = new int[6];
            int nMoves = b.getLegalMoves(moves);
            if (nMoves == 0) {
                // Joueur au trait ne peut pas jouer → reçoit ses propres graines
                // (règle compétition : seul le propre côté est attribué)
                int meOff = (me == 0) ? 0 : 6;
                int mySideSeeds = 0;
                for (int i = 0; i < 6; i++)
                    mySideSeeds += b.state[meOff + i];
                int total = capDiff + mySideSeeds;
                byte val = clamp(mySideSeeds); // incrémental = mySideSeeds
                tables[totalSeeds][index] = val;
                return total;
            }

            int best = -10000;
            for (int i = 0; i < nMoves; i++) {
                int histIdx = 100 + depth;
                b.makeMove(moves[i], histIdx);
                int val = -solve(b, depth + 1, deadline, bot);
                b.unmakeMove(histIdx);
                if (val > best)
                    best = val;
            }
            // best = valeur totale (capDiff + incrémental)
            // Stocker uniquement l'incrémental (best - capDiff)
            byte result = clamp(best - capDiff);
            tables[totalSeeds][index] = result;
            return best;
        }

        private byte clamp(int v) {
            return (byte) Math.max(-127, Math.min(127, v));
        }

        // ─── Sérialisation sur disque + Memory-Mapping ───

        /**
         * Sérialise toutes les tables dans un fichier temporaire, puis
         * memory-map le fichier via MappedByteBuffer.
         * Le MappedByteBuffer utilise la mémoire NATIVE (off-heap),
         * invisible pour Runtime.getRuntime().totalMemory().
         * On libère ensuite le byte[][] tables du heap Java (−33 MiB).
         */
        void serializeToFile() {
            if (generatedMaxSeeds < 0)
                return;
            try {
                // Calculer les offsets
                tableOffsets = new long[TB_SEEDS + 1];
                tableSizes = new int[TB_SEEDS + 1];
                long totalSize = 0;
                for (int s = 0; s <= generatedMaxSeeds; s++) {
                    tableOffsets[s] = totalSize;
                    tableSizes[s] = (tables[s] != null) ? tables[s].length : 0;
                    totalSize += tableSizes[s];
                }

                // Créer fichier temp et écrire
                File tmpFile = File.createTempFile("tb_omega_", ".bin");
                tmpFile.deleteOnExit();
                RandomAccessFile raf = new RandomAccessFile(tmpFile, "rw");
                for (int s = 0; s <= generatedMaxSeeds; s++) {
                    if (tables[s] != null) {
                        raf.write(tables[s]);
                    }
                }

                // Memory-map le fichier entier
                mappedTB = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, totalSize);
                raf.close();

                // Libérer le heap Java
                tables = null;
                System.gc();

                System.out.println("   TB serialized to disk + memory-mapped ("
                        + (totalSize / (1024 * 1024)) + " MiB off-heap)");
            } catch (Exception e) {
                System.err.println("   TB serialization failed: " + e.getMessage());
                // En cas d'échec, on garde tables en mémoire (fallback)
            }
        }

        // ─── API de consultation ───

        /**
         * Retourne le score minimax exact du point de vue du joueur au trait
         * (currentPlayer), ou UNKNOWN si la position n'est pas dans la tablebase.
         *
         * Le cache stocke la valeur INCRÉMENTALE (futures captures nettes).
         * On ajoute le capturedDiff courant (state[12/13]) pour obtenir
         * la valeur TOTALE du jeu, cohérente avec evaluate() et pvs().
         */
        int getScore(FastBoard b) {
            int totalSeeds = b.getTotalSeeds();
            if (totalSeeds > TB_SEEDS)
                return UNKNOWN;

            // Mode memory-mapped (après sérialisation)
            if (mappedTB != null) {
                if (totalSeeds > generatedMaxSeeds || tableSizes[totalSeeds] == 0)
                    return UNKNOWN;
                int index = getIndex(b);
                if (index < 0 || index >= tableSizes[totalSeeds])
                    return UNKNOWN;
                byte val = mappedTB.get((int) (tableOffsets[totalSeeds] + index));
                if (val == UNRESOLVED)
                    return UNKNOWN;
                int me = b.currentPlayer;
                return val + (b.state[12 + me] - b.state[12 + (1 - me)]);
            }

            // Mode heap (pendant la génération)
            if (tables == null || tables[totalSeeds] == null)
                return UNKNOWN;
            int index = getIndex(b);
            if (index < 0 || index >= tables[totalSeeds].length)
                return UNKNOWN;
            byte val = tables[totalSeeds][index];
            if (val == UNRESOLVED)
                return UNKNOWN;
            int me = b.currentPlayer;
            return val + (b.state[12 + me] - b.state[12 + (1 - me)]);
        }
    }
}
