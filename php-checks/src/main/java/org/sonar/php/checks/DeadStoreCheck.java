package org.sonar.php.checks;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.sonar.check.Rule;
import org.sonar.php.cfg.CfgBlock;
import org.sonar.php.cfg.ControlFlowGraph;
import org.sonar.php.cfg.LiveVariablesAnalysis;
import org.sonar.php.cfg.LiveVariablesAnalysis.LiveVariables;
import org.sonar.php.cfg.LiveVariablesAnalysis.VariableState;
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
      LiveVariables liveVariables = analysis.getLiveVariables(cfgBlock);
      Set<Symbol> out = liveVariables.getOut();
      for (Tree element : Lists.reverse(cfgBlock.elements())) {
        Map<Symbol, VariableState> usages = liveVariables.getUsages(element);
        for (Map.Entry<Symbol, VariableState> entry : usages.entrySet()) {
          Symbol symbol = entry.getKey();
          VariableState usage = entry.getValue();
          if (usage == VariableState.WRITE) {
            if (!out.contains(symbol)) {
              context().newIssue(this, element, "Found dead store!");
            }
            out.remove(symbol);
          }
          if (usage == VariableState.READ || usage == VariableState.READ_WRITE) {
            out.add(symbol);
          }
        }
      }
    }
  }
}
