/**
 * Force-directed layout kernel.
 * Calculates both repulsive and attractive forces in a single pass for each node.
 */
__kernel void compute_forces(
    __global float2* positions,
    __global const int2* edges,
    __global const int* levels,
    const int num_nodes,
    const int num_edges,
    const float k,
    const float dt,
    const int use_hierarchy)
{
    int i = get_global_id(0);
    if (i >= num_nodes) return;

    float2 pos_i = positions[i];
    float2 force = (float2)(0.0f, 0.0f);

    // 1. Repulsive forces (between all pairs)
    for (int j = 0; j < num_nodes; j++) {
        if (i == j) continue;

        float2 pos_j = positions[j];
        float2 delta = pos_i - pos_j;
        float dist = length(delta);

        if (dist > 0.1f) {
            // f_r = k^2 / dist
            force += (delta / dist) * (k * k / dist);
        }
    }

    // 2. Attractive forces (along edges)
    for (int j = 0; j < num_edges; j++) {
        int2 edge = edges[j];
        int source = edge.x;
        int target = edge.y;

        int other = -1;
        if (source == i) other = target;
        else if (target == i) other = source;

        if (other != -1) {
            float2 pos_j = positions[other];
            float2 delta = pos_j - pos_i;
            float dist = length(delta);

            if (dist > 0.1f) {
                // f_a = dist^2 / k
                force += (delta / dist) * (dist * dist / k);
            }
        }
    }

    // 3. Hierarchical force (if enabled)
    if (use_hierarchy == 1) {
        // Pull nodes towards a Y coordinate based on their BFS level
        float target_y = (levels[i] - 5) * k * 1.2f; // Offset by 5 to center around 0 roughly
        force.y += (target_y - pos_i.y) * 2.0f;
    }

    // 4. Update position
    // Limit max force to prevent explosions
    float force_len = length(force);
    if (force_len > 100.0f) {
        force = (force / force_len) * 100.0f;
    }

    // Apply damping and update
    float2 new_pos = pos_i + force * dt * 0.05f;

    // Boundary check
    new_pos.x = clamp(new_pos.x, -2000.0f, 2000.0f);
    new_pos.y = clamp(new_pos.y, -2000.0f, 2000.0f);
    
    positions[i] = new_pos;
}
