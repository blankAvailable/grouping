package jp.ac.kyutech.ci.grouping;

import java.util.Arrays;

public class ScanChainGrouperAlgS2 extends ScanChainGrouper {

	private FastCostFunction cost;

	public int[] calculateClocking(int clockCount) {

		if (cost == null) {
			cost = new FastCostFunction(chain2impactSet, cell2aggressorSet);
			log.info("FastCostFunction initialized.");
		}

		int clocking[] = new int[chains.size()];

		int upperBound = cost.evaluate(clocking, 1);
		log.info("UpperBound (by c=1) " + upperBound);

		for (int i = 0; i < clocking.length; i++)
			clocking[i] = i;
		int lowerBound = cost.evaluate(clocking, clocking.length);
		log.info("LowerBound (by c=∞) " + lowerBound);

		int[][] pairCost = calculatePairCost(clocking);

		lowerBound = Math.max(lowerBound, searchLowerBound(lowerBound, upperBound, clockCount, pairCost, clocking));
		log.info("LowerBound (after pair coloring) " + lowerBound);

		GraphColorizer g = makeGraphColorizer(clockCount, pairCost, lowerBound);
		clocking = g.colorize();
		int bestKnown = cost.evaluate(clocking, clockCount);
		log.info("BestKnownSolution (after pair coloring) " + bestKnown);

		if (lowerBound == bestKnown) {
			log.info("Returning best possible solution.");
			return clocking;
		}

		int[] edge = new int[chains.size()];
		int[] clocking_tmp = new int[clocking.length];
		System.arraycopy(clocking, 0, clocking_tmp, 0, clocking.length);

		while (true) {
			int worstClk = cost.getLastWorstClockIdx();
			int edgeSize = makeEdgeForClockIdx(worstClk, clocking_tmp, edge);
			g.addEdge(edge, edgeSize);
			clocking_tmp = g.colorize();
			if (clocking_tmp == null) {
				lowerBound = bestKnown;
				log.info("LowerBound " + lowerBound);
				return clocking;
			}
			int newCost = cost.evaluate(clocking_tmp, clockCount);
			if (newCost < bestKnown) {
				System.arraycopy(clocking_tmp, 0, clocking, 0, clocking.length);
				bestKnown = newCost;
				log.info("BestKnownSolution " + bestKnown);
			}
		}

	}

	private int[][] calculatePairCost(int[] clocking) {
		int[][] pairCost = new int[clocking.length][clocking.length];
		Arrays.fill(clocking, -1);
		int pairCount = clocking.length * clocking.length / 2;
		int pairIdx = 0;
		int progressLast = -1;
		for (int i = 0; i < clocking.length; i++) {
			clocking[i] = 0;
			for (int j = i + 1; j < clocking.length; j++) {
				clocking[j] = 0;
				pairCost[i][j] = cost.evaluate(clocking, 1);
				clocking[j] = -1;
				pairIdx++;
				int progress = 100 * pairIdx / pairCount;
				if (progress % 10 == 0 && progress != progressLast) {
					log.debug("PairCost calculation " + progress + "%% ...");
					progressLast = progress;
				}
			}
			clocking[i] = -1;
		}
		return pairCost;
	}

	private int searchLowerBound(int lb, int ub, int clockCount, int[][] pairCost, int[] solution) {
		int middle = (ub - lb) / 2 + lb;
		GraphColorizer g = makeGraphColorizer(clockCount, pairCost, middle);
		int[] s = g.colorize();
		if (s != null) {
			System.arraycopy(s, 0, solution, 0, solution.length);
			log.info("Solution for " + middle + " (" + g.countEdges() + " constraints on " + g.size() + " chains)");
			if (middle > lb)
				return searchLowerBound(lb, middle, clockCount, pairCost, solution);
			else
				return middle;
		} else {
			log.info("Conflict for " + middle + " (" + g.countEdges() + " constraints on " + g.size() + " chains)");
			if (middle < (ub - 1))
				return searchLowerBound(middle, ub, clockCount, pairCost, solution);
			else
				return middle + 1;
		}
	}

	private GraphColorizer makeGraphColorizer(int clockCount, int[][] pairCost, int costThreshold) {
		GraphColorizer g = new GraphColorizer(chains.size(), clockCount);
		for (int i = 0; i < chains.size(); i++)
			for (int j = i + 1; j < chains.size(); j++)
				if (pairCost[i][j] > costThreshold)
					g.addEdge(i, j);
		return g;
	}

	private int makeEdgeForClockIdx(int clock, int[] clocking, int[] edge) {

		int[] clocking_tmp = new int[chains.size()];
		int chainCount = 0;
		for (int c = 0; c < clocking.length; c++) {
			clocking_tmp[c] = -1;
			if (clocking[c] == clock) {
				clocking_tmp[c] = 0;
				chainCount++;
			}
		}

		int base = cost.evaluate(clocking_tmp, 1);
		int edgeSize = chainCount;
		for (int chain = 0; chain < clocking_tmp.length; chain++) {
			if (clocking_tmp[chain] == -1)
				continue;
			clocking_tmp[chain] = -1;
			if (cost.evaluate(clocking_tmp, 1) == base)
				edgeSize--;
			else
				clocking_tmp[chain] = 0;

		}
		log.info("Last worst clock: " + clock + " containing " + chainCount + " chains. adding constraint of size "
				+ edgeSize);

		edgeSize = 0;
		for (int c = 0; c < clocking_tmp.length; c++)
			if (clocking_tmp[c] == 0)
				edge[edgeSize++] = c;

		return edgeSize;
	}

}