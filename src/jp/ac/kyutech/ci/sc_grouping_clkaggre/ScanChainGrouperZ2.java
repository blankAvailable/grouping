package jp.ac.kyutech.ci.sc_grouping_clkaggre;


import java.util.Arrays;
import java.util.Random;

public class ScanChainGrouperZ2 extends ScanChainGrouper {

    private static final int INITIAL_POPULATION = 32;
    // smaller will make this algrithm finish faster
    private static final float C = (float) 1.5;

    private int[][] candClkings;

    // get initial population 64 individuals
    private void initialPopulation(int groupCount){
        Random r = new Random();
        candClkings = new int[INITIAL_POPULATION][chainSize];

        for (int indsNum = 0; indsNum < INITIAL_POPULATION; indsNum++){
            for (int j = 0; j < chainSize; j++)
                candClkings[indsNum][j] = r.nextInt(groupCount);
        }
    }

    // natural selection
    private void naturalSelection(int groupCount, int[] costList){
        int[][] tempClkings = new int[INITIAL_POPULATION][chainSize];
        float fitness[] = new float[INITIAL_POPULATION];
        float fitnessSum = 0;
        float fitnessMax = 0;
        float fitnessAvg = 0;
        float possibility[] = new float[INITIAL_POPULATION];
        float roulette = 0;
        float a = 0;
        float b = 0;
        Random r = new Random();

        for (int i = 0; i < INITIAL_POPULATION; i++){
            fitness[i] = 10000 - costList[i];
            fitnessSum += fitness[i];
            if (fitness[i] > fitnessMax)
                fitnessMax = fitness[i];
        }
        fitnessAvg = fitnessSum / INITIAL_POPULATION;
        a = fitnessAvg * (C - 1)/(fitnessMax - fitnessAvg);
        b = fitnessAvg * (fitnessMax - C * fitnessAvg)/(fitnessMax - fitnessAvg);


        // perpare the roulette
        for (int i = 0; i < INITIAL_POPULATION; i++){
            if (i == 0){
                possibility[i] = (a * fitness[i] + b)/fitnessSum;
            }else {
                possibility[i] = ((a * fitness[i] + b)/fitnessSum) + possibility[i-1];
            }
        }

        // roulette individual selection
        System.arraycopy(candClkings[getFittestIdx(costList)], 0, tempClkings[0], 0, tempClkings[0].length);
        for (int i = 1; i < INITIAL_POPULATION; i++){
            roulette = r.nextFloat();
            for (int j = 0; j < INITIAL_POPULATION; j++){
                if (roulette < possibility[0]) {
                    System.arraycopy(candClkings[0], 0, tempClkings[i], 0, candClkings[j].length);
                    break;
                }
                if (j > 0) {
                    if (roulette > possibility[j - 1] && roulette <= possibility[j]) {
                        System.arraycopy(candClkings[j], 0, tempClkings[i], 0, candClkings[j].length);
                        break;
                    }
                }
            }
        }

        //apply new population
        for (int i = 0; i < INITIAL_POPULATION; i++){
            System.arraycopy(tempClkings[i], 0, candClkings[i], 0, tempClkings[i].length);
        }
    }

    // random crossover
    private void randCrossover(){
        Random r = new Random();
        int crossOverIdx0 = 0;
        int crossOverIdx1 = 0;
        int parent0 = 0;
        int parent1 = 0;
        int checkList[] = new int[INITIAL_POPULATION];

        // two random crossover points
        while (crossOverIdx0 == crossOverIdx1){
            crossOverIdx0 = r.nextInt(chainSize);
            crossOverIdx1 = r.nextInt(chainSize);
        }

        // keep crossOverIdx0 smaller than crossOverIdx1
        if (crossOverIdx0 > crossOverIdx1){
            int temp = 0;
            temp = crossOverIdx0;
            crossOverIdx0 = crossOverIdx1;
            crossOverIdx1 = temp;
        }

        // choose parents randomly and do crossover
        int checkListSum = 0;
        while (checkListSum != INITIAL_POPULATION){
            while (parent0 == parent1 || checkList[parent0] == 1 || checkList[parent1] == 1){
                parent0 = r.nextInt(INITIAL_POPULATION);
                parent1 = r.nextInt(INITIAL_POPULATION);
            }
            checkList[parent0] = 1;
            checkList[parent1] = 1;
            checkListSum += (checkList[parent0] + checkList[parent1]);
            // swap genes between parents
            for (int j = 0; j >= crossOverIdx0; j++){
                if (j <= crossOverIdx1){
                    int temp = candClkings[parent0][j];
                    candClkings[parent0][j] = candClkings[parent1][j];
                    candClkings[parent1][j] = temp;
                }
            }
        }
    }

    // random mutation (low posibility)
    private void randMutation(int groupCount){
        Random r = new Random();

        int mutationIdx0 = r.nextInt(chainSize - 1);
        int mutationIdx1 = r.nextInt(chainSize - 1);

        for (int i = 0; i < INITIAL_POPULATION; i++){
            if (r.nextInt(9) < 1)
                candClkings[i][mutationIdx0] = r.nextInt(groupCount);
            if (r.nextInt(9) < 1)
                candClkings[i][mutationIdx1] = r.nextInt(groupCount);
        }
    }

    // get cost list
    private int[] getCostList(int groupCount, FastCostFunction cost){
        int costList[] = new int[INITIAL_POPULATION];
        for (int i = 0; i < INITIAL_POPULATION; i++){
            costList[i] = cost.evaluate(candClkings[i], groupCount);
        }
        return costList;
    }

    // get fittest clking idx
    private int getFittestIdx(int[] costList){
        int minCost = Integer.MAX_VALUE;
        int bestIdx = -1;
        for (int i = 0; i < INITIAL_POPULATION; i++){
            int currentCost = costList[i];
            if (minCost > currentCost){
                minCost = currentCost;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    @Override
    public int[] calculateClocking(int groupCount, FastCostFunction cost) {
        int generationCount = 0;
        int costList[] = new int[INITIAL_POPULATION];

        // initialize population & costList
        initialPopulation(groupCount);
        log.info("Initial population generated");
        for (int i = 0; i < INITIAL_POPULATION; i++)
            log.info("Initial population " + Arrays.toString(candClkings[i]).replaceAll("\\[", "").replaceAll
                    ("\\]", "").replaceAll(",", ""));
        System.arraycopy(getCostList(groupCount, cost), 0 , costList, 0, costList.length);

        int currentMinCost = Integer.MAX_VALUE;
        int previousMinCost = Integer.MAX_VALUE;
        int bestIdx = 0;
        int caseLimit = 6;
        while (caseLimit > 0){
            generationCount++;
            log.info("generationCount " + generationCount);

            naturalSelection(groupCount, costList);

            for (int i = 0; i < INITIAL_POPULATION; i++)
                log.info("New population " + Arrays.toString(candClkings[i]).replaceAll("\\[", "").replaceAll
                        ("\\]", "").replaceAll(",", ""));

            randCrossover();

            randMutation(groupCount);

            System.arraycopy(getCostList(groupCount, cost), 0 , costList, 0, costList.length);
            for (int i = 0; i < INITIAL_POPULATION; i++){
                if (currentMinCost > costList[i]) {
                    currentMinCost = costList[i];
                    bestIdx = i;
                }
            }
            if (currentMinCost < previousMinCost){
                previousMinCost = currentMinCost;
                caseLimit = 6;
            }
            caseLimit--;

            log.info("Generation " + generationCount + " lowest cost " + currentMinCost);
            currentMinCost = Integer.MAX_VALUE;
        }

        return candClkings[bestIdx];
    }
}