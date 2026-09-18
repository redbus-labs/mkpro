package com.mkpro.tools;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import com.google.adk.tools.BaseTool;
import com.google.genai.types.FunctionDeclaration;
import java.util.*;

public class SshToolsTest {

    @Test
    void testConnectToolArguments() {
        BaseTool tool = SshTools.createConnectTool();
        assertEquals("ssh_connect", tool.name());
        
        Optional<FunctionDeclaration> decl = tool.declaration();
        assertTrue(decl.isPresent());
        assertNotNull(decl.get().parameters());
    }

    @Test
    void testExecToolValidation() {
        BaseTool tool = SshTools.createExecTool();
        assertEquals("ssh_exec", tool.name());
        
        Optional<FunctionDeclaration> decl = tool.declaration();
        assertTrue(decl.isPresent());
        // Simplified check: verifying parameters exist
        assertNotNull(decl.get().parameters());
    }

    @Test
    void testFileTransferToolValidation() {
        BaseTool tool = SshTools.createFileTransferTool();
        assertEquals("ssh_file_transfer", tool.name());
        
        Optional<FunctionDeclaration> decl = tool.declaration();
        assertTrue(decl.isPresent());
        assertNotNull(decl.get().parameters());
    }

    @Test
    void testDisconnectToolValidation() {
        BaseTool tool = SshTools.createDisconnectTool();
        assertEquals("ssh_disconnect", tool.name());
    }
}
