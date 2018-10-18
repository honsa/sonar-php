/*
 * SonarQube PHP Plugin
 * Copyright (C) 2010-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.php.cfg;

import com.google.common.collect.Sets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.sonar.plugins.php.api.symbols.Symbol;
import org.sonar.plugins.php.api.symbols.SymbolTable;
import org.sonar.plugins.php.api.tree.Tree;
import org.sonar.plugins.php.api.tree.declaration.VariableDeclarationTree;
import org.sonar.plugins.php.api.tree.expression.ArrayAssignmentPatternElementTree;
import org.sonar.plugins.php.api.tree.expression.AssignmentExpressionTree;
import org.sonar.plugins.php.api.tree.expression.UnaryExpressionTree;
import org.sonar.plugins.php.api.tree.expression.VariableIdentifierTree;
import org.sonar.plugins.php.api.visitors.PHPVisitorCheck;

public class LiveVariablesAnalysis {

  private final ControlFlowGraph controlFlowGraph;
  private final Map<CfgBlock, LiveVariables> liveVariablesPerBlock;

  private LiveVariablesAnalysis(ControlFlowGraph cfg, SymbolTable symbols) {
    controlFlowGraph = cfg;
    liveVariablesPerBlock = compute(controlFlowGraph, symbols);
  }

  public LiveVariables getLiveVariables(CfgBlock block) {
    return liveVariablesPerBlock.get(block);
  }

  public static LiveVariablesAnalysis analyze(ControlFlowGraph cfg, SymbolTable symbols) {
    return new LiveVariablesAnalysis(cfg, symbols);
  }

  private static Map<CfgBlock, LiveVariables> compute(ControlFlowGraph cfg, SymbolTable symbols) {
    Map<CfgBlock, LiveVariables> liveVariablesPerBlock = new HashMap<>();
    cfg.blocks().forEach(block -> {
      LiveVariables liveVariables = new LiveVariables(block, symbols);
      liveVariablesPerBlock.put(block, liveVariables);
    });

    Deque<CfgBlock> worklist = new ArrayDeque<>(cfg.blocks());
    while (!worklist.isEmpty()) {
      CfgBlock block = worklist.pop();
      LiveVariables liveVariables = liveVariablesPerBlock.get(block);
      boolean liveInHasChanged = liveVariables.propagate(liveVariablesPerBlock);
      if (liveInHasChanged) {
        block.predecessors().forEach(worklist::push);
      }
    }

    return liveVariablesPerBlock;
  }

  public static enum VariableState {
    NONE,
    WRITE,
    READ,
    READ_WRITE
  }

  public static class LiveVariables {

    private final CfgBlock block;
    private final SymbolTable symbols;
    private final Map<Tree, Map<Symbol, VariableState>> usagesPerElement;
    // 'gen' or 'use' - variables that are being read in the block
    private final Set<Symbol> gen = new HashSet<>();
    // 'kill' or 'def' - variables that are being written (TODO before being read in the block)
    private final Set<Symbol> kill = new HashSet<>();
    // the 'in' and 'out' change during the algorithm
    private Set<Symbol> in = new HashSet<>();
    private Set<Symbol> out = new HashSet<>();

    LiveVariables(CfgBlock block, SymbolTable symbols) {
      this.block = block;
      this.symbols = symbols;
      this.usagesPerElement = new HashMap<>();
      initialize();
    }

    public Set<Symbol> getIn() {
      // TODO immutable
      return in;
    }

    public Set<Symbol> getOut() {
      // TODO immutable
      return out;
    }

    public Map<Symbol, VariableState> getUsages(Tree tree) {
      return usagesPerElement.get(tree);
    }

    Set<Symbol> getGen() {
      return gen;
    }

    Set<Symbol> getKill() {
      return kill;
    }

    boolean propagate(Map<CfgBlock, LiveVariables> liveVariablesPerBlock) {
      // propagate the out values backwards, from successors
      out.clear();
      block.successors().stream().map(liveVariablesPerBlock::get).map(df -> df.in).forEach(out::addAll);
      // in = gen + (out - kill)
      Set<Symbol> newIn = new HashSet<>(gen);
      newIn.addAll(Sets.difference(out, kill));
      boolean inHasChanged = !newIn.equals(in);
      in = newIn;
      return inHasChanged;
    }

    /**
     * TODO: The idea is to avoid false positives.
     * So for each element of the basic block, which can be any kind of Tree, I'll have a Visitor which will give a state for each symbols inside it - R, W or RW.
     * We only add to 'kill' if the Symbol inside the Expression is 'WRITE'
     * If its 'READ' or '{READ, WRITE}', we consider it as READ
     */
    private void initialize() {
      // process elements from bottom to top
      Set<Symbol> killedVars = new HashSet<>();

      for (Tree tree : block.elements()) {

        ReadWriteVisitor visitor = new ReadWriteVisitor(symbols);
        tree.accept(visitor);

        Map<Symbol, VariableState> usages = visitor.getState();
        usagesPerElement.put(tree, usages);

        for (Map.Entry<Symbol, VariableState> usage : usages.entrySet()) {
          VariableState state = usage.getValue();

          if (state == VariableState.READ || state == VariableState.READ_WRITE) {
            if (!killedVars.contains(usage.getKey())) {
              gen.add(usage.getKey());
            }
          }

          if (state == VariableState.WRITE || state == VariableState.READ_WRITE) {
            kill.add(usage.getKey());
            if (state != VariableState.READ_WRITE) {
              killedVars.add(usage.getKey());
            }
          }

        }
      }
    }

  }

  private static class ReadWriteVisitor extends PHPVisitorCheck {
    private final SymbolTable symbols;
    private final Map<Symbol, VariableState> variables = new HashMap<>();

    ReadWriteVisitor(SymbolTable symbols) {
      this.symbols = symbols;
    }

    Map<Symbol, VariableState> getState() {
      return variables;
    }

    @Override
    public void visitAssignmentExpression(AssignmentExpressionTree tree) {
      if (!visitAssignedVariable(tree.variable())) {
        tree.variable().accept(this);
      }
      tree.value().accept(this);
    }

    @Override
    public void visitArrayAssignmentPatternElement(ArrayAssignmentPatternElementTree tree) {
      visitAssignedVariable(tree.variable());
      super.visitArrayAssignmentPatternElement(tree);
    }

    @Override
    public void visitVariableIdentifier(VariableIdentifierTree tree) {
      visitReadVariable(tree);
      super.visitVariableIdentifier(tree);
    }

    // TODO is the below accurrate?
    @Override
    public void visitVariableDeclaration(VariableDeclarationTree tree) {
      visitAssignedVariable(tree.identifier());
      super.visitVariableDeclaration(tree);
    }

    @Override
    public void visitPrefixExpression(UnaryExpressionTree tree) {
      visitReadVariable(tree);
      visitAssignedVariable(tree);
      super.visitPrefixExpression(tree);
    }

    private boolean visitAssignedVariable(Tree tree) {
      if (!tree.is(Tree.Kind.VARIABLE_IDENTIFIER)) {
        return false;
      }
      Symbol varSym = symbols.getSymbol(tree);

      variables.compute(varSym, (s, existingState) -> {
        if (existingState == null) {
          existingState = VariableState.NONE;
        }
        switch (existingState) {
          case READ_WRITE:
          case READ:
            return VariableState.READ_WRITE;
          default:
            return VariableState.WRITE;
        }
      });

      return true;
    }

    private void visitReadVariable(Tree tree) {
      if (!tree.is(Tree.Kind.VARIABLE_IDENTIFIER)) {
        return;
      }
      Symbol varSym = symbols.getSymbol(tree);
      variables.compute(varSym, (s, existingState) -> {
        if (existingState == null) {
          existingState = VariableState.NONE;
        }
        switch (existingState) {
          case READ_WRITE:
          case WRITE:
            return VariableState.READ_WRITE;
          default:
            return VariableState.READ;
        }
      });
    }
  }
}
