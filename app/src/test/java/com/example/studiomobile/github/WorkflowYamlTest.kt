package com.example.studiomobile.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowYamlTest {
    @Test
    fun plainDispatchWithoutInputs() {
        val spec = WorkflowYaml.parse("name: Build\non:\n  workflow_dispatch:\njobs:\n  a:\n    runs-on: x\n")
        assertTrue(spec.manual)
        assertTrue(spec.inputs.isEmpty())
    }

    @Test
    fun scalarAndListForms() {
        assertTrue(WorkflowYaml.parse("on: workflow_dispatch\n").manual)
        assertTrue(WorkflowYaml.parse("on: [push, workflow_dispatch]\njobs: {}\n").manual)
    }

    @Test
    fun workflowWithoutManualTrigger() {
        val spec = WorkflowYaml.parse("on:\n  push:\n    branches: [main]\n  pull_request:\n")
        assertFalse(spec.manual)
    }

    @Test
    fun booleanInput() {
        val yaml = """
            name: Build JRE
            on:
              workflow_dispatch:
                inputs:
                  publish:
                    description: 'Publish a GitHub Release (tag jre-bundle-r<BUNDLE_REVISION> from config.env)'
                    type: boolean
                    default: false
                    required: false
            jobs:
              b:
                runs-on: x
                steps:
                  - run: |
                      echo "on: nothing"
        """.trimIndent()
        val input = WorkflowYaml.parse(yaml).inputs.single()
        assertEquals("publish", input.key)
        assertEquals("boolean", input.type)
        assertEquals("false", input.defaultValue)
        assertFalse(input.required)
        assertTrue(input.description.startsWith("Publish a GitHub Release"))
    }

    @Test
    fun quotedOnKeyAndStringInputs() {
        val yaml = """
            "on":
              workflow_dispatch:
                inputs:
                  tag:
                    description: "Tag rilis Flutter (juga nama release di GitHub)"
                    required: true
                    default: "3.47.7"   # versi
                  size:
                    description: "(Opsional) Ukuran: byte"
                    required: false
        """.trimIndent()
        val inputs = WorkflowYaml.parse(yaml).inputs
        assertEquals(2, inputs.size)
        assertEquals("3.47.7", inputs[0].defaultValue)
        assertTrue(inputs[0].required)
        assertEquals("string", inputs[0].type)
        assertEquals("(Opsional) Ukuran: byte", inputs[1].description)
        assertEquals("", inputs[1].defaultValue)
    }

    @Test
    fun choiceInputWithBlockAndInlineOptions() {
        val yaml = """
            on:
              workflow_dispatch:
                inputs:
                  target:
                    type: choice
                    options:
                      - debug
                      - release
                  mode:
                    type: choice
                    options: [fast, slow, "very slow"]
                    default: slow
        """.trimIndent()
        val inputs = WorkflowYaml.parse(yaml).inputs
        assertEquals(listOf("debug", "release"), inputs[0].options)
        assertEquals("debug", inputs[0].defaultValue)
        assertEquals(listOf("fast", "slow", "very slow"), inputs[1].options)
        assertEquals("slow", inputs[1].defaultValue)
    }

    @Test
    fun optionsListAtTheSameIndentAsItsKey() {
        val yaml = "on:\n  workflow_dispatch:\n    inputs:\n      c:\n        type: choice\n        options:\n        - a\n        - b\n"
        assertEquals(listOf("a", "b"), WorkflowYaml.parse(yaml).inputs.single().options)
    }

    @Test
    fun blockScalarDescription() {
        val yaml = "on:\n  workflow_dispatch:\n    inputs:\n      x:\n        description: |\n          line one\n          line two\n        required: true\n"
        val input = WorkflowYaml.parse(yaml).inputs.single()
        assertEquals("line one\nline two", input.description)
        assertTrue(input.required)
    }

    @Test
    fun scheduleDoesNotHideDispatch() {
        val yaml = "on:\n  workflow_dispatch:\n  schedule:\n    - cron: '0 0 * * *'\n"
        assertTrue(WorkflowYaml.parse(yaml).manual)
    }

    @Test
    fun missingOnSection() {
        assertFalse(WorkflowYaml.parse("name: x\njobs: {}\n").manual)
    }
}
