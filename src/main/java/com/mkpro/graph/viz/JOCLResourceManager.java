package com.mkpro.graph.viz;

import com.mkpro.graph.*;
import org.jocl.*;

import java.io.InputStream;
import java.util.Scanner;

import static org.jocl.CL.*;

/**
 * Handles initialization and cleanup of OpenCL resources.
 */
public class JOCLResourceManager implements AutoCloseable {
    private cl_context context;
    private cl_command_queue commandQueue;
    private cl_device_id device;

    public JOCLResourceManager() {
        initOpenCL();
    }

    private void initOpenCL() {
        CL.setExceptionsEnabled(true);
        final int platformIndex = 0;
        final long deviceType = CL_DEVICE_TYPE_ALL;
        final int deviceIndex = 0;

        int[] numPlatformsArray = new int[1];
        clGetPlatformIDs(0, null, numPlatformsArray);
        int numPlatforms = numPlatformsArray[0];
        cl_platform_id[] platforms = new cl_platform_id[numPlatforms];
        clGetPlatformIDs(platforms.length, platforms, null);
        cl_platform_id platform = platforms[platformIndex];

        int[] numDevicesArray = new int[1];
        clGetDeviceIDs(platform, deviceType, 0, null, numDevicesArray);
        int numDevices = numDevicesArray[0];
        cl_device_id[] devices = new cl_device_id[numDevices];
        clGetDeviceIDs(platform, deviceType, numDevices, devices, null);
        device = devices[deviceIndex];

        cl_context_properties contextProperties = new cl_context_properties();
        contextProperties.addProperty(CL_CONTEXT_PLATFORM, platform);
        context = clCreateContext(contextProperties, 1, new cl_device_id[]{device}, null, null, null);

        @SuppressWarnings("deprecation")
        cl_command_queue cq = clCreateCommandQueue(context, device, 0, null);
        commandQueue = cq;
    }

    public cl_program createProgramFromResource(String resourcePath) {
        String source = loadResource(resourcePath);
        cl_program program = clCreateProgramWithSource(context, 1, new String[]{source}, null, null);
        clBuildProgram(program, 0, null, null, null, null);
        return program;
    }

    private String loadResource(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) throw new RuntimeException("Could not find OpenCL kernel at " + path);
            try (Scanner s = new Scanner(is).useDelimiter("\\A")) {
                return s.hasNext() ? s.next() : "";
            }
        } catch (Exception e) {
            throw new RuntimeException("Error loading OpenCL kernel: " + path, e);
        }
    }

    public cl_context getContext() { return context; }
    public cl_command_queue getCommandQueue() { return commandQueue; }

    @Override
    public void close() {
        if (commandQueue != null) clReleaseCommandQueue(commandQueue);
        if (context != null) clReleaseContext(context);
    }
}
