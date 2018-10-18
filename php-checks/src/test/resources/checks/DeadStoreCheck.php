<?php

  function foo($a) {
    $x = 0;// Compliant - default value
    $x = 3; // Noncompliant
    $x = 4;
    $y = $x + 1; // Noncompliant {{Remove this useless assignment to local variable "$y".}}
    $x = 2; // Noncompliant {{Remove this useless assignment to local variable "$x".}}
    $x = 3;
    $y = 2;
    foo($y);
    foo($x);
    $a = new Object();
    $a = null; // Noncompliant
  }