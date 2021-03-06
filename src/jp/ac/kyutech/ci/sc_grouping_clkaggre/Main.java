package jp.ac.kyutech.ci.sc_grouping_clkaggre;

import jdk.internal.dynalink.ChainedCallSite;
import jp.ac.kyutech.ci.sc_grouping_clkaggre.QBWeightedSwitchingActivitySim.WeightedNodeSet;

import org.kyupi.circuit.*;
import org.kyupi.circuit.MutableCircuit.MutableCell;
import org.kyupi.circuit.ScanChains.ScanCell;
import org.kyupi.circuit.ScanChains.ScanChain;
import org.kyupi.data.QVExpander;
import org.kyupi.data.item.QVector;
import org.kyupi.data.source.BBSource;
import org.kyupi.data.source.QBSource;
import org.kyupi.data.source.QVSource;
import org.kyupi.misc.KyupiApp;
import org.kyupi.misc.StringFilter;
import org.kyupi.sim.BBPlainSim;

import java.io.*;
import java.util.*;
import java.util.function.Predicate;

public class Main extends KyupiApp {

    // SAED90 row eight is 2880nm
    // NAND2X0 and NAND2X1 cell width is 1920nm
    // def file units are nm.

    private static final int NAND_WIDTH = 1920;
    private static final int ROW_HEIGHT = 2880;

    public static void main(String[] args) throws Exception {
        new Main().setArgs(args).call();
    }

    public Main() {
        // input parameters
        options.addOption("def", true, "read cell placement from given def file");
        options.addOption("clk", true, "number of staggered groups");
        options.addOption("arx", true, "horizontal size of aggressor regions in units of NAND2X1 widths");
        options.addOption("ary", true, "vertical size of aggressor regions in units of rows");
        options.addOption("prt_method", true, "partitioning method: seq, random, z1, ... (default seq)");
        options.addOption("prt_start", true, "start partition index (seq) or start seed (random) (default 0)");
        options.addOption("prt_cases", true, "number of partitions to evaluate (for seq, random only) (default 1)");

        // specific operations to perform
        options.addOption("sep_clk", true, "safe a new design with separate chain clocks to given file and exit");
        options.addOption("sim", true, "evaluate by WSA sim with given number of blocks (1 block = 32 shift cycles)");

        // output control parameters
        options.addOption("table", true, "output a data table for latex into given file");
        // output data for excel
        options.addOption("plot", true, "output a data table for latex into given file");
        options.addOption("zpl", true, "input the name of the zpl file");
        options.addOption("thr", true, "input the skew threshold for the ILP model or heuristic");
        options.addOption("sol", true, "read the solution from the ILP model to get the optimized grouping");
    }

    private MutableCircuit mcircuit;
    private LevelizedCircuit circuit;
    /**
     * Computes a result, or throws an exception if unable to do so.
     * balance clock aggressor number through scan chain grouping
     * @return computed result
     * @throws Exception if unable to compute a result
     */
    @Override
    public Void call() throws Exception {
        printWelcome();

        // load circuit and print basic statistics
        setLib(new LibraryNewSAED90());
        mcircuit = loadNextCircuitFromArgs();
        mcircuit.printStats();
        int nodecount = mcircuit.countNonPseudoNodes();
        log.info("NonPseudoNodes " + nodecount);

        //extract scan chains and clock paths
        ScanChains chains = new ScanChains(mcircuit);
        CBInfo cbinfo = collectClockBuffers(mcircuit, chains);
        log.info("ScanChainCount " + chains.size());
        log.info("MaxChainLength " + chains.maxChainLength());

        // realize sep_clk operation. Exits here if executed.
        if(argsParsed().hasOption("sep_clk")){
            String filename = argsParsed().getOptionValue("sep_clk");
            separateClks(chains, cbinfo);
            FileOutputStream output = new FileOutputStream(filename);
            FormatVerilog.save(output, mcircuit);
            output.close();
            printGoodbye();
            return null;
        }
        
        circuit = mcircuit.levelized();
        
        chains = new ScanChains(circuit);
        cbinfo = collectClockBuffers(circuit, chains);


        // extract impact area/set
        log.info("Calculating impact sets...");
        HashMap<ScanChain, HashSet<Cell>> chain2impactset = new HashMap<>();
        calculateImpactSets(chains, cbinfo, chain2impactset);

        // load placement info from .def file
        Placement placement = new Placement(circuit);
        if (argsParsed().hasOption("def")){
            String filename = argsParsed().getOptionValue("def");
            placement.parseDefFile(filename, new StringFilter() {
                @Override
                public String filter(String source) { return source.replaceAll("\\\\", ""); }
            });
        }

        // read aggressor region size parameters
        double arx = doubleFromArgsOrDefault("arx", 200);
        double ary = doubleFromArgsOrDefault("ary", 8);
        int arxnm = (int) (arx * NAND_WIDTH);
        int arynm = (int) (ary * ROW_HEIGHT);
        log.info("AggressorRegionSize X " + arx + " Y " + ary);
        log.info("AggressorRegionSizeNM X " + arxnm + " Y " + arynm);

        // extract clock aggressor sets
        HashMap<Cell, HashSet<Cell>> cbuf2aggressorSet = new HashMap<>();
        HashMap<ScanCell, ArrayList<Cell>> cell2aggressorSet = new HashMap<>();
        calculateAggressorSets(cbinfo, chains, placement, arxnm, arynm, cbuf2aggressorSet, cell2aggressorSet);
        printAggressorAndImpactInfo(chains, cell2aggressorSet, chain2impactset);


        // read in grouping paremeters
        int clocks = intFromArgsOrDefault("clk", 1);
        int skewthreshold = intFromArgsOrDefault("thr", 0);
        log.info("AvailableGroupCount " + clocks);
        ScanChainGrouping grouping = null;
        ScanChainGrouper grouper = null;
        String groupingMethod = stringFromArgsOrDefault("prt_method", "random").toLowerCase();
        long startSeed = longFromArgsOrDefault("prt_start", 0);
        long groupingCases = longFromArgsOrDefault("prt_cases", 1);
        if (clocks == 1){
            log.info("AvailableGroupCount = 1");
        }else if (clocks > chains.size()) {
            log.info("AvailableGroupCount Larger Than ChainCount, One Chain Per Group");
        }else if (groupingMethod.startsWith("r")) {
                log.info("GroupingMethod Random");
                log.info("GroupingStart " + startSeed);
                grouping = new RandomGrouping(chains.size(), clocks, startSeed);
            } else if (groupingMethod.startsWith("se")) {
                log.info("GroupingMethod Sequential");
                log.info("GroupingStart " + startSeed);
                grouping = new SeqGrouping(chains.size(), clocks);
                for (int i = 0; i < startSeed; i++) {
                    if (grouping.hasNext()) {
                        grouping.next();
                    } else {
                        log.info("startSeed out of bound");
                        grouping.iterator();
                    }
                }
            } else if (groupingMethod.startsWith("z1")) {
                log.info("GroupingMethod Z1");
                grouper = new ScanChainGrouperZ1();
            } else if (groupingMethod.startsWith("z2")) {
                log.info("GroupingMethod Z2");
                grouper = new ScanChainGrouperZ2();
            }else if (groupingMethod.startsWith("z3")) {
                log.info("GroupingMethod Z3");
                grouper = new ScanChainGrouperZ3();
            } else if (groupingMethod.startsWith("z5")){
                log.info("GroupingMethod Z5");
                grouper = new ScanChainGrouperZ5(skewthreshold);
            } else {
                log.error("unknown grouping method " + groupingMethod);
                printGoodbye();
                return null;
            }

        // set algorithm parameters, if an algorithm is selected
        if (grouper != null){
            grouper.setChainSize(chains.size());
            if (groupingCases > 1)
                log.warn("prt_cases is ignored, only a sigle grouping is evaluated.");
            if (startSeed > 0)
                log.warn("prt_start is ignored, only a sigle grouping is evaluated");
            groupingCases = 1;
            startSeed = 0;
        }

        ScanChianGrouperZ4 zpl = null;
        if (argsParsed().hasOption("zpl") && clocks < chains.size()) {
            String filename = argsParsed().getOptionValue("zpl");
            zpl = new ScanChianGrouperZ4(chain2impactset, cell2aggressorSet, skewthreshold);
            zpl.ZplWriter(filename, clocks);
            //zpl.testCase();
        }

        FastCostFunction cost = new FastCostFunction(chain2impactset, cell2aggressorSet);

        BufferedWriter plot = null;
        if (argsParsed().hasOption("plot")) {
            String filename = argsParsed().getOptionValue("plot");
            File plotWriter = new File(filename);
            plotWriter.createNewFile();
            plot = new BufferedWriter(new FileWriter(plotWriter));
        }
        //start grouping
        int avgCost = 0;
        for (int caseId = 0; caseId < groupingCases; caseId++){
            log.info("GroupingCase " + caseId);
            int clocking[];

            if (grouper != null){
                log.info("ScanChainGrouping start with " + clocks +" available groups... ");
                clocking = grouper.calculateClocking(clocks, cost);
                log.info("ScanChainGrouping finished.");
            }else if (grouping != null){
                if (!grouping.hasNext()){
                    log.error("prt_start+caseId out of bounds, starting over");
                    grouping.iterator();
                }
                clocking = grouping.next();
            }else if (clocks > chains.size()){
                clocks = chains.size();
                clocking = new int[chains.size()];
                for (int i = 0; i < chains.size(); i++){
                    clocking[i] = i;
                }
            }else {
                clocking = new int[chains.size()];
            }

            if (argsParsed().hasOption("sol") && clocks < chains.size()){
                String filename = argsParsed().getOptionValue("sol");
                if (zpl == null)
                    zpl = new ScanChianGrouperZ4(chain2impactset, cell2aggressorSet, skewthreshold);
                clocking = zpl.SolReader(filename, clocks);
            }

            avgCost += cost.evaluate(clocking, clocks);

            if (plot != null){
                log.info("CostDifference " + cost.evaluate(clocking, clocks));
                plot.write(" " + caseId + " " + cost.groupCost[0] + "\n");
                log.info("GroupCost " + Arrays.toString(cost.groupCost).replaceAll("\\[", "").replaceAll("\\]", "")
                        .replaceAll(",", "") + " Worst group " + cost.getLastWorstClockId());
            }else{
                log.info("CostDifference " + cost.evaluate(clocking, clocks));
                log.info("GroupCost " + Arrays.toString(cost.groupCost).replaceAll("\\[", "").replaceAll("\\]", "")
                        .replaceAll(",", "") + " Worst group " + cost.getLastWorstClockId());
            }


            // print grouping info and grouping cost
            log.info("Clocking " + Arrays.toString(clocking).replaceAll("\\[", "").replaceAll("\\]", "")
                    .replaceAll(",", ""));

            if (!argsParsed().hasOption("sim"))
                continue;

            int blocks = Integer.parseInt(argsParsed().getOptionValue("sim"));


            log.info("WSA simulation setup... ");
            QBSource shifts = prepareExpandedRandomPatterns(chains, clocking);
            QBWeightedSwitchingActivitySim sim = new QBWeightedSwitchingActivitySim(circuit, shifts);
            HashMap<ScanCell, WeightedNodeSet> aggressorWNSet = new HashMap<>();
            for (ScanCell saff : cell2aggressorSet.keySet()){
                WeightedNodeSet wnSet = sim.new WeightedNodeSet();

                for (Cell n : cell2aggressorSet.get(saff)){
                    wnSet.add(n, 1.0);
                }
                aggressorWNSet.put(saff, wnSet);
            }

            WeightedNodeSet overallwnSet = sim.new WeightedNodeSet();
            for (ScanChain chain : chain2impactset.keySet()){
                for (Cell n : chain2impactset.get(chain)){
                    overallwnSet.add(n, 1.0);
                }
            }

            log.info("WSA simulation started... ");
            for (int i = 0; i < blocks; i++)
                sim.next();
            log.info("WSA simulation finished.");

            double overallActivityDiffMax = 0.0;
            double worstactivity1 = 0.0;
            double worstactivity2 = 0.0;
            for (int chainId = 0; chainId < chains.size(); chainId++){

                //WSA difference may large somewhere else, but at this those places are not activated.
                //Only need to count the WSA difference at current group
                if (clocking[chainId] != cost.getLastWorstClockId())
                    continue;

                ScanChain chain = chains.get(chainId);
                // int clock_phase = clocking[chainId]
                log.info("Chain " + chainId + " ScanInPort " + chain.in.node.name());
                WeightedNodeSet wns1;
                double activity1 = 0.0;
                WeightedNodeSet wns2;
                double activity2 = 0.0;
                boolean flag = false;
                for (int patternId = 0; patternId < blocks * 32; patternId++){
                    for (ScanCell cell : chain.cells){
                        if (flag == false) {
                            wns1 = aggressorWNSet.get(cell);
                            activity1 = wns1.getActivity(patternId);
                            flag = true;
                        }else {
                            wns2 = aggressorWNSet.get(cell);
                            activity2 = wns2.getActivity(patternId);
                            flag = false;
                            if (overallActivityDiffMax < Math.abs(activity1 - activity2)){
                                overallActivityDiffMax = Math.abs(activity1 - activity2);
                                worstactivity1 = activity1;
                                worstactivity2 = activity2;
                            }
                        }
                    }
                }
            }
            log.info("OverallMaxWSADiff " + overallActivityDiffMax);
            log.info("WorstActivity1 " + worstactivity1);
            log.info("WorstActivity2 " + worstactivity2);

            double sumacticity = 0.0;
            for (int patternId = 0; patternId < blocks * 32; patternId++)
                sumacticity+=overallwnSet.getActivity(patternId);
            log.info("OverallAvgWSA " + (sumacticity/(blocks*32)));

        } // caseId loop

        log.info("AverageCost " + avgCost/groupingCases);

        if (plot != null)
            plot.close();

        printGoodbye();
        return null;
    }

    private CBInfo collectClockBuffers(Circuit circuit, ScanChains sc){
        log.info("Collecting clock buffers for each scan cell");

        CBInfo clkbuf = new CBInfo();
        HashMap<Cell, ArrayList<Cell>> saff2cb = new HashMap<>();
        HashSet<Cell> all_cbuf = new HashSet<>();

        for(int chain = 0; chain < sc.size(); chain++){
            ScanChain c = sc.get(chain);
            for(int chainpos = 0; chainpos < c.cells.size(); chainpos++){
                ScanCell saff = c.cells.get(chainpos);
                Cell saffnode = saff.node;
                Cell cbuf = saffnode.inputCellAt(getLib().getClockPin(saffnode.type()));
                ArrayList<Cell> cb = collectClockBuffers(cbuf, new ArrayList<Cell>());
                StringBuffer strbuf = new StringBuffer();
                for(Cell n : cb){
                    strbuf.append(" " + n.name());
                }
                saff2cb.put(saffnode, cb);
                all_cbuf.addAll(cb);
            }
        }

        int saff_num = saff2cb.size();
        int cbuf_num = 0;
        int cbuf_max = 0;
        for(Cell saff : saff2cb.keySet()){
            HashSet<Cell> cbufset = new HashSet<>();
            clkbuf.sff_to_clock_buffer_set.put(saff, cbufset);
            int cbuf = 0;
            for(Cell cb : saff2cb.get(saff)){
                if((!cb.isPseudo() && !cb.isInput())){
                    cbufset.add(cb);
                    cbuf++;
                }
            }
            cbuf_num +=cbuf;
            cbuf_max = Math.max(cbuf_max, cbuf);
        }
        int cbuf_count = 0;
        log.debug("hash set size " + all_cbuf.size());
        for(Cell cb : all_cbuf){
            if((!cb.isPseudo() && !cb.isInput())){
                clkbuf.all_clock_buffers.add(cb);
                cbuf_count++;
            }
        }
        log.info("ClockBufferCount " + cbuf_count);
        log.info("ScanCellCount " + saff_num);
        log.info("MaxClockBufferPerScanCell " + cbuf_max);

        return clkbuf;
    }

    private ArrayList<Cell> collectClockBuffers(Cell head, ArrayList<Cell> tail){
        if (head == null)
            return tail;
        tail.add(head);
        if (head.inputCount() > 1) {
            if (head.isType(LibrarySAED90.TYPE_CGLPPR)) {
                return collectClockBuffers(head.inputCellAt(getLib().pinIndex(LibrarySAED90.TYPE_CGLPPR, "CLK")), tail);
            } else {
                log.error("found odd gate in clock tree, terminating here: " + head);
                return tail;
            }
        } else {
            return collectClockBuffers(head.inputCellAt(0), tail);
        }
    }

    private void separateClks(ScanChains chains, CBInfo cbinfo){
        MutableCell oriClk = mcircuit.searchCellByName("clock");
        oriClk.remove();
        int intfNodeIdx = mcircuit.width();
        for(int chainId = 0; chainId < chains.size(); chainId++){
            ScanChain chain = chains.get(chainId);
            MutableCell clk = mcircuit.new MutableCell(String.format("clk%03d", chainId), LibrarySAED90.TYPE_BUF | Library.FLAG_INPUT);
            clk.setIntfPosition(intfNodeIdx++);
            for(ScanCell cell : chain.cells){
                mcircuit.connect(clk, -1, (MutableCell) cell.node, mcircuit.library().getClockPin(cell.node.type()));
            }
        }
    }

    private void calculateImpactSets(ScanChains chains, CBInfo cbinfo, HashMap<ScanChain, HashSet<Cell>> chain2impactset){
        for(int chainId = 0; chainId < chains.size(); chainId++){
            ScanChain chain = chains.get(chainId);
            HashSet<Cell> impactset = new HashSet<Cell>();
            chain2impactset.put(chain, impactset);
            for(ScanCell cell : chain.cells){
                impactset.add(cell.node);
                impactset.addAll(CircuitTools.collectCombinationalOutputCone(cell.node));
                if (cbinfo.sff_to_clock_buffer_set.get(cell) != null)
                    impactset.addAll(cbinfo.sff_to_clock_buffer_set.get(cell));
            }
            impactset.removeIf(new Predicate<Cell>() {
                @Override
                public boolean test(Cell node) { return node.isPseudo(); }
            });
        }
    }

    private void calculateAggressorSets(CBInfo cbInfo, ScanChains chains, Placement placement, int arxnm, int arynm, HashMap<Cell,
            HashSet<Cell>> cbuf2aggressorSet, HashMap<ScanCell, ArrayList<Cell>> cell2aggressorSet){
        for (int chainId = 0; chainId < chains.size(); chainId++){
            ScanChain chain = chains.get(chainId);
            for (ScanCell cell : chain.cells){
                ArrayList<Cell> saffaggressors = new ArrayList<>();
                cell2aggressorSet.put(cell, saffaggressors);
                for (Cell n : cbInfo.sff_to_clock_buffer_set.get(cell.node)){
                    int x = placement.getX(n);
                    int y = placement.getY(n);
                    System.out.print(x + " " + y + "\t");
                    cbuf2aggressorSet.put(n, placement.getRectangle(x - arxnm / 2, y - arynm / 2, x + arxnm / 2,
                            y+ arynm / 2));
                    //no duplication removing
                    saffaggressors.addAll(cbuf2aggressorSet.get(n));
                }
                System.out.println();
            }
        }
    }

    private void printAggressorAndImpactInfo(ScanChains chains, HashMap<ScanCell, ArrayList<Cell>> cell2aggressorSet, HashMap<ScanChain, HashSet<Cell>> chain2impactSet) throws IOException {
        BufferedWriter table = null;
        if (argsParsed().hasOption("table")){
            String filename = argsParsed().getOptionValue("table");
            File tableWriter = new File(filename);
            tableWriter.createNewFile();
            table = new BufferedWriter(new FileWriter(tableWriter));
        }
        for (int chainId = 0; chainId < chains.size(); chainId++){
            ScanChain chain = chains.get(chainId);
            log.info("Chain " + chainId + " ScanInPort " + chain.in.node.name());
            log.info("  ChainLength " + chain.cells.size());
            int aggmin = Integer.MAX_VALUE;
            int aggmax = 0;
            int aggsum = 0;
            int aggsizePredecessor = 0;
            int maxAggDiff = 0;
            for (ScanCell saff : chain.cells){
                int aggsize = cell2aggressorSet.get(saff).size();
                aggmin = Math.min(aggmin, aggsize);
                aggmax = Math.max(aggmax, aggsize);
                aggsum += aggsize;
                if (aggsizePredecessor == 0){
                    aggsizePredecessor = aggsize;
                    continue;
                }else if (Math.abs((aggsizePredecessor - aggsize)) > maxAggDiff){
                    maxAggDiff = Math.abs((aggsizePredecessor - aggsize));
                    aggsizePredecessor = aggsize;
                }
            }
            int aggavg = aggsum / chain.cells.size();
            log.info(" AggressorsPerScanCell Min" + aggmin + " Avg " + aggavg + " Max " + aggmax + " MaxDifference "
                    + maxAggDiff);
            log.info(" ImpactCellCount " + chain2impactSet.get(chain).size());
            if (table != null)
                table.write(chainId + " & " + " & " + aggavg + " & " + maxAggDiff + "\\\\\n");
        }
        if (table != null)
            table.close();
    }

    private QBSource prepareExpandedRandomPatterns(ScanChains chains, int[] clocking) {
        int stimuliExpansionMap[][] = expandForWsa(chains.scanInMapping(clocking));
        int responseExpansionMap[][] = expandForWsa(chains.scanOutMapping(clocking));
        BBSource stimuli = BBSource.random(circuit.width(), 42);
        BBSource responses = BBPlainSim.from(stimuli);
        // FIXME remove first pattern from stimuli for proper alignment
        QVSource stimuliExpanded = new QVExpander(QVSource.from(stimuli), stimuliExpansionMap);
        QVSource responsesExpanded = new QVExpander(QVSource.from(responses), responseExpansionMap);

        QVSource shifts = new QVSource(stimuli.length()) {

            @Override
            public void reset() {
                stimuliExpanded.reset();
                responsesExpanded.reset();
            }

            @Override
            protected QVector compute() {
                if (!stimuliExpanded.hasNext() || !responsesExpanded.hasNext())
                    return null;
                QVector combined = pool.alloc();
                QVector s = stimuliExpanded.next();
                s.copyTo(0, combined);
                s.free();
                QVector r = responsesExpanded.next();
                combined.or(r);
                r.free();
                return combined;
            }
        };
        return QBSource.from(shifts);
    }


    /**
     * WSA simulator always calculates WSA between pattern pairs 0~1, 2~3, 4~5,
     * and so on. This function doubles the appropriate rows in a scan mapping
     * for shift-cycle WSA.
     *
     * a b -> a b
     *
     * a b c -> a b b c
     *
     * a b c d -> a b b c c d
     *
     * @param map
     * @return
     */
    private int[][] expandForWsa(int[][] map) {
        int expandedMap[][] = new int[(map.length - 1) * 2][];
        for (int i = 0; i < map.length; i++) {
            if (expandedMap.length > i * 2) {
                expandedMap[i * 2] = Arrays.copyOf(map[i], map[i].length);
            }
            if (i > 0) {
                expandedMap[i * 2 - 1] = Arrays.copyOf(map[i], map[i].length);
            }
        }
        return expandedMap;
    }
}
