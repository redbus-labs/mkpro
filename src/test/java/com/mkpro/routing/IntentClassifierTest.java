package com.mkpro.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IntentClassifier category detection.
 */
public class IntentClassifierTest {

    private final IntentClassifier classifier = new IntentClassifier();

    // ==========================================================================
    // Architecture / Project Analysis
    // ==========================================================================

    @ParameterizedTest
    @CsvSource({
        "'deep analyze entire project', ARCHITECTURE",
        "'analyze the project structure', ARCHITECTURE",
        "'analyze code quality', ARCHITECTURE",
        "'review the entire codebase', ARCHITECTURE",
        "'scan project for issues', ARCHITECTURE",
        "'give me an overview of this project', ARCHITECTURE",
        "'how does this project work', ARCHITECTURE",
        "'how is this structured', ARCHITECTURE",
        "'explore the codebase', ARCHITECTURE",
        "'refactor the service layer', ARCHITECTURE",
        "'design a new module', ARCHITECTURE",
        "'review the architecture', ARCHITECTURE",
        "'check coupling between classes', ARCHITECTURE",
        "'high level summary', ARCHITECTURE",
    })
    void classifiesArchitecture(String input, IntentClassifier.TaskCategory expected) {
        assertEquals(expected, classifier.classify(input));
    }

    // ==========================================================================
    // SysAdmin
    // ==========================================================================

    @ParameterizedTest
    @CsvSource({
        "'count number of lines in each file', SYSADMIN",
        "'how many files are there', SYSADMIN",
        "'list all files in src', SYSADMIN",
        "'find all java files', SYSADMIN",
        "'run the build', SYSADMIN",
        "'execute the script', SYSADMIN",
        "'compile the project', SYSADMIN",
        "'clean and package', SYSADMIN",
        "'size of the project', SYSADMIN",
        "'start the server', SYSADMIN",
        "'npm run dev', SYSADMIN",
        "'maven build', SYSADMIN",
    })
    void classifiesSysAdmin(String input, IntentClassifier.TaskCategory expected) {
        assertEquals(expected, classifier.classify(input));
    }

    // ==========================================================================
    // Coding
    // ==========================================================================

    @ParameterizedTest
    @CsvSource({
        "'implement user login', CODING",
        "'fix the null pointer exception', CODING",
        "'add a new endpoint for users', CODING",
        "'write a function to parse JSON', CODING",
        "'the app is crashing on startup', CODING",
        "'rename the variable', CODING",
        "'modify the service class', CODING",
        "'edit the config file', CODING",
    })
    void classifiesCoding(String input, IntentClassifier.TaskCategory expected) {
        assertEquals(expected, classifier.classify(input));
    }

    // ==========================================================================
    // Git
    // ==========================================================================

    @ParameterizedTest
    @CsvSource({
        "'commit all changes', GIT",
        "'push to main', GIT",
        "'create a new branch', GIT",
        "'what changed recently', GIT",
    })
    void classifiesGit(String input, IntentClassifier.TaskCategory expected) {
        assertEquals(expected, classifier.classify(input));
    }

    // ==========================================================================
    // Testing
    // ==========================================================================

    @ParameterizedTest
    @CsvSource({
        "'write tests for the controller', TESTING",
        "'run the test suite', TESTING",
        "'check code coverage', TESTING",
        "'validate the output', TESTING",
    })
    void classifiesTesting(String input, IntentClassifier.TaskCategory expected) {
        assertEquals(expected, classifier.classify(input));
    }

    // ==========================================================================
    // GENERAL — should NOT match any specific category
    // ==========================================================================

    @ParameterizedTest
    @CsvSource({
        "'hello'",
        "'yes go ahead'",
        "'ok'",
        "'thanks'",
        "'what do you think'",
    })
    void classifiesGeneral(String input) {
        assertEquals(IntentClassifier.TaskCategory.GENERAL, classifier.classify(input));
    }

    // ==========================================================================
    // Confidence
    // ==========================================================================

    @Test
    void highConfidenceForSpecificInput() {
        double conf = classifier.classifyWithConfidence("write unit tests for the auth module");
        assertTrue(conf >= 0.3, "Expected confidence >= 0.3, got " + conf);
    }

    @Test
    void zeroConfidenceForGeneral() {
        double conf = classifier.classifyWithConfidence("ok");
        assertEquals(0.0, conf);
    }

    @Test
    void confidenceScalesWithMatches() {
        double single = classifier.classifyWithConfidence("commit");
        double multi = classifier.classifyWithConfidence("commit and push to the branch");
        assertTrue(multi > single, "More matches should give higher confidence");
    }

    // ==========================================================================
    // Edge cases
    // ==========================================================================

    @Test
    void nullReturnsGeneral() {
        assertEquals(IntentClassifier.TaskCategory.GENERAL, classifier.classify(null));
    }

    @Test
    void emptyReturnsGeneral() {
        assertEquals(IntentClassifier.TaskCategory.GENERAL, classifier.classify(""));
    }

    @Test
    void nullConfidenceReturnsZero() {
        assertEquals(0.0, classifier.classifyWithConfidence(null));
    }
}
