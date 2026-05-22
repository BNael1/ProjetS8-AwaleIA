package awele.run;

import java.util.ArrayList;
import java.util.Set;
import java.util.Scanner;

import org.reflections.Reflections;

import awele.bot.Bot;
import awele.core.Awele;
import awele.core.InvalidBotException;
import awele.output.LogFileOutput;
import awele.output.OutputWriter;
import awele.output.StandardOutput;
import javassist.Modifier;
import java.util.concurrent.TimeUnit;

/**
 * @author Inspiré de Main par Alexandre Blansché
 * Programme de duel entre deux bots spécifiques
 * Permet de tester rapidement deux bots sans attendre le tournoi complet
 */
public final class Duel extends OutputWriter
{
    private static Duel instance = null;
    private static final String LOG_FILE = "duel.log";
    private static final String ANONYMOUS_LOG_FILE = "duel.anonymous.log";


    // Nombre de matchs pour le duel
    private static final int NB_RUNS = 50;

    ArrayList<Bot> bots;

    /**
     * @return Retourne l'instance de Duel
     */
    public static Duel getInstance()
    {
        if (Duel.instance == null)
            Duel.instance = new Duel();
        return Duel.instance;
    }

    private Duel()
    {
    }

    private static String formatDuration(final long l)
    {
        final long hr = TimeUnit.MILLISECONDS.toHours(l);
        final long min = TimeUnit.MILLISECONDS.toMinutes(l - TimeUnit.HOURS.toMillis(hr));
        final long sec = TimeUnit.MILLISECONDS.toSeconds(l - TimeUnit.HOURS.toMillis(hr) - TimeUnit.MINUTES.toMillis(min));
        final long ms = TimeUnit.MILLISECONDS.toMillis(l - TimeUnit.HOURS.toMillis(hr) - TimeUnit.MINUTES.toMillis(min) - TimeUnit.SECONDS.toMillis(sec));
        return String.format("%02d:%02d:%02d.%03d", hr, min, sec, ms);
    }

    private static String formatMemory(long bytes)
    {
        int unit = 1024;
        if (bytes < unit) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = ("KMGTPE").charAt(exp - 1) + "i";
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }

    private static long getUsedMemory()
    {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private void loadBots()
    {
        long startLoading = System.currentTimeMillis();

        Reflections reflections = new Reflections("awele.bot");
        ArrayList<Class<? extends Bot>> subClasses = new ArrayList<Class<? extends Bot>>();

        Set<Class<? extends Bot>> subClassesTmp = reflections.getSubTypesOf(Bot.class);
        for (Class<? extends Bot> subClass : subClassesTmp)
        {
            if (!Modifier.isAbstract(subClass.getModifiers()))
                subClasses.add(subClass);
        }

        this.bots = new ArrayList<Bot>();
        int index = 0;
        for (Class<? extends Bot> subClass : subClasses)
        {
            try
            {
                Bot bot = (Bot) subClass.getConstructors()[0].newInstance();
                if (bot != null)
                {
                    this.bots.add(bot);
                }
            }
            catch (Exception e)
            {
                // Silencieusement ignorer les bots qui ne peuvent pas être instanciés
            }
        }

        long endLoading = System.currentTimeMillis();
        this.print("✅ " + this.bots.size() + " bots chargés en " + Duel.formatDuration(endLoading - startLoading));
    }

    private void duel()
    {
        Bot bot1 = null;
        Bot bot2 = null;

        // Afficher la liste des bots
        this.print();
        this.print("═══════════════════════════════════════════");
        this.print("BOTS DISPONIBLES");
        this.print("═══════════════════════════════════════════");
        for (int i = 0; i < this.bots.size(); i++)
        {
            this.print((i + 1) + ". " + this.bots.get(i).getName() + " (" + this.bots.get(i).getClass().getSimpleName() + ")");
        }
        this.print();

        // Scanner pour les entrées utilisateur
        Scanner scanner = new Scanner(System.in);

        // Choix du premier bot
        int choice1 = -1;
        while (choice1 < 1 || choice1 > this.bots.size())
        {
            this.print("Choisissez le numéro du Bot 1 (1-" + this.bots.size() + ") :");
            try
            {
                choice1 = scanner.nextInt();
                if (choice1 < 1 || choice1 > this.bots.size())
                    this.print("❌ Choix invalide !");
            }
            catch (Exception e)
            {
                scanner.nextLine(); // Vider le buffer
                this.print("❌ Entrée invalide !");
            }
        }
        bot1 = this.bots.get(choice1 - 1);

        // Choix du deuxième bot
        int choice2 = -1;
        while (choice2 < 1 || choice2 > this.bots.size())
        {
            this.print("Choisissez le numéro du Bot 2 (1-" + this.bots.size() + ") :");
            try
            {
                choice2 = scanner.nextInt();
                if (choice2 < 1 || choice2 > this.bots.size())
                    this.print("❌ Choix invalide !");
            }
            catch (Exception e)
            {
                scanner.nextLine(); // Vider le buffer
                this.print("❌ Entrée invalide !");
            }
        }
        bot2 = this.bots.get(choice2 - 1);

        scanner.close();

        this.print();
        this.print("═══════════════════════════════════════════");
        this.print("DUEL : " + bot1.getName() + " vs. " + bot2.getName());
        this.print("═══════════════════════════════════════════");
        this.print("Nombre de matchs : " + Duel.NB_RUNS);
        this.print();

        double[] localPoints = new double[2];
        double nbMoves = 0;
        long runningTime = 0;
        int nbDraws = 0;
        int[] wins = {0, 0};

        long startDuel = System.currentTimeMillis();

        for (int k = 0; k < Duel.NB_RUNS; k++)
        {
            Awele awele = new Awele(bot1, bot2);
            try
            {
                awele.play();
            }
            catch (InvalidBotException e)
            {
                e.printStackTrace();
            }
            nbMoves += awele.getNbMoves();
            runningTime += awele.getRunningTime();

            if (awele.getWinner() >= 0)
            {
                localPoints[awele.getWinner()] += 3;
                wins[awele.getWinner()]++;
            }
            else
            {
                localPoints[0]++;
                localPoints[1]++;
                nbDraws++;
            }

            // Affichage de la progression
            if ((k + 1) % 10 == 0)
                this.print("Progression : " + (k + 1) + "/" + Duel.NB_RUNS + " matchs joués");
        }

        long endDuel = System.currentTimeMillis();

        localPoints[0] /= Duel.NB_RUNS;
        localPoints[1] /= Duel.NB_RUNS;
        nbMoves /= Duel.NB_RUNS;
        runningTime /= Duel.NB_RUNS;

        this.print();
        this.print("═══════════════════════════════════════════");
        this.print("RÉSULTATS");
        this.print("═══════════════════════════════════════════");
        this.print();
        this.print("📊 SCORE MOYEN");
        this.print("  " + bot1.getName() + " : " + String.format("%.2f", localPoints[0]));
        this.print("  " + bot2.getName() + " : " + String.format("%.2f", localPoints[1]));
        this.print();
        this.print("🏆 VICTOIRES");
        this.print("  " + bot1.getName() + " : " + wins[0] + " victoires");
        this.print("  " + bot2.getName() + " : " + wins[1] + " victoires");
        this.print("  Égalités : " + nbDraws);
        this.print();

        if (localPoints[0] > localPoints[1])
            this.print("✅ " + bot1.getName() + " a gagné !");
        else if (localPoints[1] > localPoints[0])
            this.print("✅ " + bot2.getName() + " a gagné !");
        else
            this.print("🤝 Égalité entre les deux bots !");

        this.print();
        this.print("📈 STATISTIQUES");
        this.print("  Nombre de coups par match : " + String.format("%.0f", nbMoves));
        this.print("  Durée moyenne par match : " + Duel.formatDuration((long) runningTime));
        this.print("  Durée totale du duel : " + Duel.formatDuration(endDuel - startDuel));
        this.print("  Mémoire utilisée : " + Duel.formatMemory(Duel.getUsedMemory()));
        this.print();
        this.print("═══════════════════════════════════════════");
    }

    /**
     * @param args
     */
    public static void main(String[] args)
    {
        Duel duel = Duel.getInstance();
        duel.addOutput(StandardOutput.getInstance());
        duel.addOutput(new LogFileOutput(Duel.LOG_FILE));
        duel.addOutput(new LogFileOutput(Duel.ANONYMOUS_LOG_FILE, true));

        duel.print("🎮 Démarrage du mode DUEL");
        duel.print();

        duel.loadBots();
        duel.duel();
    }
}
