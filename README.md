# Intelligence Artificielle pour le jeu d'Awalé

Moteur de jeu compétitif pour l'**Awalé** (jeu de semailles traditionnel d'Afrique de l'Ouest), conçu dans le cadre du cours d'Intelligence Artificielle du **Master 1 Informatique** à l'Université de Lorraine (2025-2026).

## Auteurs

Projet réalisé en binôme :
- **Naël BENSAADI**
- **Hugo MORLET**

## Contexte

Le bot dispose d'une **heure d'apprentissage** avant le tournoi, puis doit prendre ses décisions en **moins de 100 ms par coup** en moyenne (mono-thread, sans calcul distribué). Une base de 303 situations de jeu issues de parties expérimenté / novice est fournie comme ressource d'entraînement.

## Architecture du moteur

Approche inspirée des moteurs d'échecs modernes, adaptée aux contraintes de l'Awalé.

### Représentation et mémoire

- **FastBoard** : représentation interne sur un unique `int[14]` (12 trous + 2 scores), copie/restauration par primitive — **zéro allocation** dynamique pendant la recherche, élimine totalement les pauses du Garbage Collector Java.
- **Table de Transposition** en *Structure of Arrays* (`long[] hash`, `int[] score`, `byte[] depth/flag/move/gen`) avec **hachage Zobrist** sur 64 bits.
- **Dimensionnement dynamique** : 2²⁴ entrées (~256 MiB) en apprentissage, 2¹⁹ (~8 MiB) en jeu, avec **condensation mémoire** des entrées les plus profondes après apprentissage.

### Algorithme de recherche

- **Principal Variation Search** (PVS) avec fenêtre nulle
- **Approfondissement itératif** + **fenêtres d'aspiration** + **Internal Iterative Deepening**
- **Time banking** : crédit accumulé sur les coups rapides (Opening Book, Tablebase), redistribué jusqu'à 180 ms pour les positions critiques
- **5 techniques d'élagage** : Reverse Futility Pruning, Razoring, Late Move Pruning, Futility Pruning, Delta Pruning
- **Late Move Reductions** (LMR) avec table de réductions logarithmique pré-calculée
- **Quiescence Search** en feuille
- **Ordonnancement des coups** : TT-move, Killer Moves, Countermove, History Heuristic, bonus K-NN

### Fonction d'évaluation

Combinaison linéaire de **14 caractéristiques** (avantage matériel, mobilité, vulnérabilités, captures potentielles, *kroo*, tempo, etc.), avec :
- **Initialisation par T-Statistic** sur les 154 situations gagnantes du dataset
- **Bonus K-NN** : à la racine, les 15 positions les plus proches du dataset orientent l'ordonnancement vers les coups historiquement victorieux

### Pipeline d'apprentissage (55 min)

| Phase | Durée | Description |
|---|---|---|
| Initialisation | < 1 min | Chargement CSV, calcul des poids T-Statistic |
| **Algorithme Génétique** | 10 min | 40 individus, sélection par tournoi, croisement uniforme, mutation gaussienne |
| **Endgame Tablebase** | 15 min | Analyse rétrograde de toutes les positions ≤ 15 graines, indexation combinadique (~33 MiB), accès *memory-mapped* |
| **Opening Book** | ~30 min | Auto-jeu profond depuis la position initiale, condensation TT → livre d'ouverture immuable |

## Stack technique

- **Java** (mono-thread, conformité règlement)
- **Framework** : awele (fourni — JARs dans [lib/](lib/))

## Structure du dépôt

```
.
├── src/awele/bot/competitor/TieLeOmegaBottMk3/   # Code du bot
├── src/awele/run/                                 # Points d'entrée (Main, Duel, DepthTest)
├── data/                                          # Dataset CSV (303 situations)
├── lib/                                           # Dépendances du framework
└── rapport/                                       # Rapport PDF
    └── rapport_awale.pdf
```

## Compilation et exécution

```bash
# Compiler les sources Java
javac -cp "lib/*" -d bin src/awele/**/*.java

# Lancer un duel
java -cp "bin:lib/*" awele.run.Duel
```

## Rapport détaillé

Le rapport complet (algorithmes, dérivations, choix de conception) est disponible dans **[rapport/rapport_awale.pdf](rapport/rapport_awale.pdf)**.
