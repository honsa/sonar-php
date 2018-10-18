package org.sonar.php.checks;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.sonar.check.Rule;
import org.sonar.php.cfg.CfgBlock;
import org.sonar.php.cfg.ControlFlowGraph;
import org.sonar.php.cfg.LiveVariablesAnalysis;
import org.sonar.plugins.php.api.symbols.Symbol;
import org.sonar.plugins.php.api.tree.Tree;
import org.sonar.plugins.php.api.visitors.PHPSubscriptionCheck;

@Rule(key = "S1854")
public class DeadStoreCheck extends PHPSubscriptionCheck {


  @Override
  public List<Tree.Kind> nodesToVisit() {
    return ImmutableList.copyOf(ControlFlowGraph.KINDS_WITH_CONTROL_FLOW);
  }



  @Override
  public void visitNode(Tree tree) {
    ControlFlowGraph cfg = ControlFlowGraph.build(tree, context());
    if (cfg == null) {
      return;
    }

    LiveVariablesAnalysis analysis = LiveVariablesAnalysis.analyze(cfg, context().symbolTable());

    for (CfgBlock cfgBlock : cfg.blocks()) {
      LiveVariablesAnalysis.LiveVariables liveVariables = analysis.getLiveVariables(cfgBlock);
      Set<Symbol> out = liveVariables.getOut();
      for (Tree element : Lists.reverse(cfgBlock.elements())) {
        Map<Symbol, EnumSet<LiveVariablesAnalysis.VariableState>> usages = liveVariables.getUsages(element);
        for (Map.Entry<Symbol, EnumSet<LiveVariablesAnalysis.VariableState>> entry : usages.entrySet()) {
          Symbol symbol = entry.getKey();
          EnumSet<LiveVariablesAnalysis.VariableState> usage = entry.getValue();
          if (usage.size() == 1 && usage.contains(LiveVariablesAnalysis.VariableState.WRITE)) {
            if (!out.contains(symbol)) {
              context().newIssue(this, element, "Found dead store!");
            }
            out.remove(symbol);
          }
          if (usage.contains(LiveVariablesAnalysis.VariableState.READ)) {
            out.add(symbol);
          }
        }
      }
    }
  }
}
