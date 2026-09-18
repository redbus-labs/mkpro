package com.mkpro.graph.viz;

import com.mkpro.graph.*;
import org.jocl.*;

import static org.jocl.CL.*;

/**
 * Manages the OpenCL program and kernels for graph layout.
 */
public class LayoutKernelProvider implements AutoCloseable {
    private final JOCLResourceManager resourceManager;
    private final cl_program program;
    private final cl_kernel layoutKernel;

    public LayoutKernelProvider() {
        this.resourceManager = new JOCLResourceManager();
        // Correctly point to kernels/ in resources
        this.program = resourceManager.createProgramFromResource("kernels/layout.cl");
        this.layoutKernel = clCreateKernel(program, "compute_forces", null);
    }

    public cl_kernel getKernel() { return layoutKernel; }
    public cl_context getContext() { return resourceManager.getContext(); }
    public cl_command_queue getCommandQueue() { return resourceManager.getCommandQueue(); }

    @Override
    public void close() {
        clReleaseKernel(layoutKernel);
        clReleaseProgram(program);
        resourceManager.close();
    }
}
