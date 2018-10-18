<?php

  function foo($a) {
    $x = 42; // Noncompliant
    $x = 3;
    return $x;
  }