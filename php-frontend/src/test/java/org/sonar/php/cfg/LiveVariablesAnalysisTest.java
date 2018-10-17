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

import org.junit.Test;
import org.sonar.php.PHPTreeModelTest;
import org.sonar.php.parser.PHPLexicalGrammar;
import org.sonar.php.tree.symbols.SymbolTableImpl;
import org.sonar.plugins.php.api.tree.CompilationUnitTree;
import org.sonar.plugins.php.api.tree.declaration.FunctionDeclarationTree;
import org.sonar.plugins.php.api.tree.declaration.FunctionTree;

/**
 * This Live Variable Analysis Test uses a meta-language to specify the expected LVA values for each basic block.
 *
 * Convention:
 *
 * 1. the metadata is specified as a function call with the form:
 *
 * {@code block1( succ = [block2, END], liveIn = [$x, $y], liveOut = [$y], gen = [$x, $y], kill = [$x] ); }
 * where the argument is a bracketed array with 3 elements:
 * - 'succ' is a bracketed array of expected successor ids. For branching blocks, the true successor must be first.
 * - 'liveIn'  - the live variables that enter the block
 * - 'liveOut' - the live variables that exit the block
 * - 'gen'     - the variables that are consumed by the block
 * - 'kill'    - the variables that are killed (overwritten) by the block
 *
 * 2. each basic block must contain a function call with this structure as the first statement
 * - exception: a Label is before the block function call
 *
 * 3. the name of the function is the identifier of the basic block
 *
 * Also check {@link ExpectedCfgStructure} and {@link ControlFlowGraphTest}
 */
public class LiveVariablesAnalysisTest extends PHPTreeModelTest {

  @Test
  public void test_simple_kill() {
    verifyLiveVariableAnalysis("" +
      "block( succ = [END], liveIn = [], liveOut = [], gen = [], kill = [$foo, $bar, $qix]);" +
      "$foo = 1;" +
      "$bar = bar();" +
      "$qix = 1 + 2;");

  }

  @Test
  public void test_simple_gen() {
    verifyLiveVariableAnalysis("" +
      "block( succ = [END], liveIn = [$foo, $bar], liveOut = [], gen = [$foo, $bar]);" +
      "foo($foo, $bar);");
  }

  @Test
  public void test_gen_kill() {
    verifyLiveVariableAnalysis("" +
      "condition( succ = [body, END], liveIn = [], liveOut = [], gen = [], kill = [$a]);" +
      "$a = $x + 1;" +
      "foo($a);" +
      "if (true) {" +
      "  body( succ = [END], liveIn = [], liveOut = [], gen = [], kill = [$x]);" +
      "  $x = 1;" +
      "}");

    verifyLiveVariableAnalysis("" +
      "condition( succ = [body, END], liveIn = [$a], liveOut = [], gen = [$a], kill = [$a]);" +
      "foo($a);" +
      "$a = $x + 1;" +
      "if (true) {" +
      "  body( succ = [END], liveIn = [], liveOut = [], gen = [], kill = [$x]);" +
      "  $x = 1;" +
      "}");
  }

  @Test
  public void test_do_while() {
    verifyLiveVariableAnalysis("" +
      //"beforeDo( succ = [body], liveIn = [$x], liveOut = [$a], gen = [$x], kill = [$a]);" +
      "beforeDo( succ = [body], liveIn = [], liveOut = [$a], gen = [], kill = [$a]);" +
      "$a = $x + 1;" +
      "do {" +
      "  body( succ = [cond], liveIn = [$a], liveOut = [$a], gen = [$a], kill = []);" +
      "  foo ($a);" +
      "} while(cond( succ = [body, afterDo], liveIn = [$a], liveOut = [$a], gen = [], kill = []) );" +
      "afterDo( succ = [END], liveIn = [], liveOut = [], gen = [], kill = [$a]);" +
      "$a = 0;");
  }

  private void verifyLiveVariableAnalysis(String body) {
    verifyLiveVariableAnalysis("", body);
  }

  private void verifyLiveVariableAnalysis(String argsList, String body) {
    CompilationUnitTree cut = parse("<?php function f(" + argsList + ") { " + body + " }", PHPLexicalGrammar.COMPILATION_UNIT);
    SymbolTableImpl symbolTable = SymbolTableImpl.create(cut);
    FunctionDeclarationTree functionTree = (FunctionDeclarationTree)cut.script().statements().get(0);
    ControlFlowGraph cfg = ControlFlowGraph.build(functionTree.body());
    LiveVariablesAnalysis analysis = LiveVariablesAnalysis.analyze(cfg, symbolTable);
    Validator.assertLiveVariables(cfg, analysis);
  }

}
