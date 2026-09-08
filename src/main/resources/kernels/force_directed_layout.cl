/**
 * Simple OpenCL kernel for force-directed layout (Fruchterman-Reingold base).
 * This kernel calculates the displacement of nodes based on repulsive forces.
 */
__kernel void compute_repulsive_forces(
    __global const float2* positions,
    __global float2* displacements,
    const int num_nodes,
    const float k)
{
    int i = get_global_id(0);
    if (i >= num_nodes) return;

    float2 pos_i = positions[i];
    float2 disp = (float2)(0.0f, 0.0f);

    for (int j = 0; j < num_nodes; j++) {
        if (i == j) continue;

        float2 pos_j = positions[j];
        float2 delta = pos_i - pos_j;
        float dist = length(delta);

        // Avoid division by zero and extremely close nodes
        if (dist < 0.01f) {
            dist = 0.01f;
        }

        // Repulsive force: f_r(d) = k^2 / d
        disp += (delta / dist) * (k * k / dist);
    }

    displacements[i] = disp;
}

/**
 * Updates node positions based on displacement and applies temperature cooling.
 */
__kernel void update_positions(
    __global float2* positions,
    __global const float2* displacements,
    const int num_nodes,
    const float temp,
    const float width,
    const float height)
{
    int i = get_global_id(0);
    if (i >= num_nodes) return;

    float2 disp = displacements[i];
    float disp_len = length(disp);

    if (disp_len > 0.0f) {
        // Limit displacement by temperature and move node
        float2 limited_disp = (disp / disp_len) * fmin(disp_len, temp);
        float2 new_pos = positions[i] + limited_disp;

        // Keep nodes within bounds
        new_pos.x = fmax(0.0f, fmin(width, new_pos.x));
        new_pos.y = fmax(0.0f, fmin(height, new_pos.y));

        positions[i] = new_pos;
    }
}
