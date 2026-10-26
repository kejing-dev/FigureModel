package io.embodit.figuremodel.ui.fm.scene

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.figuremodel.domain.model.Quat4
import io.embodit.figuremodel.domain.model.Vec3
import io.embodit.figuremodel.platform.FmLog
import io.embodit.figuremodel.ui.theme.FmColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. The scene works in Stage metres: every entity hangs below the calibrated
// stage root, whose local space equals Stage space (same approach as the Spatial Ruler).
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setUniformScale(k: Float) {
    transformOrNull?.setScaleVector(Vector3(k, k, k))
}

internal fun Entity.setVisible(visible: Boolean) {
    components[ModelComponent::class.java]?.isVisible = visible
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

/** Overall opacity of an entity and its children (fades). */
internal fun Entity.setOpacity(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f)
    else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

internal fun Entity.named(name: String): Entity {
    runCatching { setName(name) }
    return this
}

/** A box collider of [size] (local metres) that the system pointer can tap. */
internal fun Entity.tapBox(size: Vec3, offset: Vec3 = Vec3.ZERO) {
    val shape = ShapeResource.createBox(size.sdk()).let { if (offset == Vec3.ZERO) it else it.offsetByTranslation(offset.sdk()) }
    components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = PhysicsMaterialResource()))
    components.set(InteractableComponent())
}

/**
 * Resources shared by entities that come and go (materials, meshes). The SDK releases a resource when
 * its last user entity is destroyed; shared ones are made global and closed when the scene goes away.
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/**
 * Scene-lifetime materials, one per appearance. The wood is lit (PBR, matte) so that the app's directional
 * light draws the light and shadow sides (design `rules.lightImpl`, first choice); everything that marks
 * (rings, orb, disc) is unlit with tone mapping off so that the design colours are exact.
 */
internal class SceneMaterials(private val shared: SharedResources) {
    val wood: Material = lit(FmColors.Wood, roughness = 0.82f)
    val woodDark: Material = lit(FmColors.WoodDark, roughness = 0.75f)
    val gypsum: Material = lit(FmColors.Gypsum, roughness = 0.95f)

    /** Stand ring: interaction yellow, full and 25 % (the figure stands on it), green flash. */
    val ring: Material = sheer(FmColors.Interaction, 1f, depthTest = false)
    val ringDim: Material = sheer(FmColors.Interaction, 0.25f, depthTest = false)
    val ringOk: Material = sheer(FmColors.Passable, 1f, depthTest = false)

    /** Relocation ghost ring: yellow 60 %, error red on a table for the life-size figure. */
    val ghost: Material = sheer(FmColors.Interaction, 0.6f, depthTest = false)
    val ghostBad: Material = sheer(FmColors.Error, 0.75f, depthTest = false)

    /** Timer ring: charcoal track at 20 %, ink arc, alert arc in the last five seconds. */
    val track: Material = sheer(FmColors.Charcoal, 0.2f, depthTest = false)
    val arc: Material = sheer(FmColors.Ink, 0.95f, depthTest = false)
    val arcAlert: Material = sheer(FmColors.Alert, 1f, depthTest = false)

    /** The light orb (self-lit warm white) and its short beam. */
    val orb: Material = unlit(FmColors.LightWarm)
    val beam: Material = sheer(FmColors.LightWarm, 0.7f)

    /** Next disc: charcoal 70 %, white icon, interaction progress. */
    val disc: Material = sheer(FmColors.Charcoal, 0.7f)
    val discIcon: Material = unlit(FmColors.White, twoSided = true)
    val progress: Material = unlit(FmColors.Interaction, twoSided = true)

    private fun lit(color: Color, roughness: Float): PhysicallyBasedMaterial =
        shared.keep(
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                setMetallic(0f)
                setRoughness(roughness)
                // a faint self-glow keeps the shadow side wood-coloured in a room without ambient light
                setEmissiveColor(Color4(color.red * GLOW, color.green * GLOW, color.blue * GLOW, 1f))
            }
        )

    private fun unlit(color: Color, twoSided: Boolean = false): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                runCatching { setApplyToneMapping(false) }
                if (twoSided) setCullingMode(MaterialCullingMode.NONE)
            }
        )

    private companion object {
        const val GLOW = 0.04f
    }

    /** A see-through unlit material: the colour is opaque, the material's opacity carries the share. */
    private fun sheer(color: Color, opacity: Float, depthTest: Boolean = true): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
                if (!depthTest) setDepthTest(false)
            }
        )
}

/** Programmatic meshes (`MeshModel`: positions, indices, normals, optional uv). */
internal object MeshFactory {
    private val UP = Vector3(0f, 1f, 0f)
    private val FRONT = Vector3(0f, 0f, 1f)

    /** Solid disc in the local XZ plane, facing +Y. */
    fun disc(radius: Float, name: String, segments: Int = 40): MeshResource {
        val positions = ArrayList<Vector3>(segments + 1)
        val normals = ArrayList<Vector3>(segments + 1)
        val indices = ArrayList<Int>(segments * 3)
        positions += Vector3(0f, 0f, 0f)
        normals += UP
        for (i in 0 until segments) {
            val a = 2 * PI * i / segments
            positions += Vector3((cos(a) * radius).toFloat(), 0f, (sin(a) * radius).toFloat())
            normals += UP
        }
        for (i in 0 until segments) {
            indices += 0
            indices += 1 + (i + 1) % segments
            indices += 1 + i
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(radius, 0.001f, radius)),
            name = name,
        )
    }

    /**
     * Disc in the local XY plane facing +Z with texture coordinates that map the unit square onto it:
     * the icon plates (the texture's circle fills the disc exactly, no transparency needed).
     */
    fun discUv(radius: Float, name: String, segments: Int = 40): MeshResource {
        val positions = ArrayList<Vector3>(segments + 1)
        val normals = ArrayList<Vector3>(segments + 1)
        val uvs = ArrayList<Vector2>(segments + 1)
        val indices = ArrayList<Int>(segments * 3)
        positions += Vector3(0f, 0f, 0f)
        normals += FRONT
        uvs += Vector2(0.5f, 0.5f)
        for (i in 0 until segments) {
            val a = 2 * PI * i / segments
            val x = cos(a).toFloat()
            val y = sin(a).toFloat()
            positions += Vector3(x * radius, y * radius, 0f)
            normals += FRONT
            uvs += Vector2(0.5f + x * 0.5f, 0.5f - y * 0.5f)
        }
        for (i in 0 until segments) {
            indices += 0
            indices += 1 + i
            indices += 1 + (i + 1) % segments
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals, uv0 = uvs),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(radius, radius, 0.001f)),
            name = name,
        )
    }

    /** Flat ring in the local XZ plane, facing +Y; with [dashes] > 0 only that many arcs are drawn. */
    fun ring(inner: Float, outer: Float, name: String, dashes: Int = 0, dashFill: Float = 0.6f, segments: Int = 48): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        fun arc(a0: Double, a1: Double, steps: Int) {
            for (i in 0 until steps) {
                val s = a0 + (a1 - a0) * i / steps
                val e = a0 + (a1 - a0) * (i + 1) / steps
                val base = positions.size
                positions += Vector3((cos(s) * inner).toFloat(), 0f, (sin(s) * inner).toFloat())
                positions += Vector3((cos(s) * outer).toFloat(), 0f, (sin(s) * outer).toFloat())
                positions += Vector3((cos(e) * outer).toFloat(), 0f, (sin(e) * outer).toFloat())
                positions += Vector3((cos(e) * inner).toFloat(), 0f, (sin(e) * inner).toFloat())
                repeat(4) { normals += UP }
                indices += base
                indices += base + 2
                indices += base + 1
                indices += base
                indices += base + 3
                indices += base + 2
            }
        }
        if (dashes <= 0) {
            arc(0.0, 2 * PI, segments)
        } else {
            val slot = 2 * PI / dashes
            for (d in 0 until dashes) arc(d * slot, d * slot + slot * dashFill, 3)
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(outer, 0.001f, outer)),
            name = name,
        )
    }

    /** Strip x 0..1, y -0.5..0.5 facing +Z: scaled to any length and width (needle, leaders). */
    fun unitBand(name: String): MeshResource {
        val positions = listOf(Vector3(0f, -0.5f, 0f), Vector3(1f, -0.5f, 0f), Vector3(1f, 0.5f, 0f), Vector3(0f, 0.5f, 0f))
        val normals = List(4) { FRONT }
        val indices = listOf(0, 1, 2, 0, 2, 3)
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0.5f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.002f)),
            name = name,
        )
    }

    /** Closed cylinder of [radius] and [height] along +Y from y = 0. */
    fun cylinder(radius: Float, height: Float, name: String, segments: Int = 20): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        // side
        for (i in 0 until segments) {
            val a0 = 2 * PI * i / segments
            val a1 = 2 * PI * (i + 1) / segments
            val n0 = Vector3(cos(a0).toFloat(), 0f, sin(a0).toFloat())
            val n1 = Vector3(cos(a1).toFloat(), 0f, sin(a1).toFloat())
            val base = positions.size
            positions += Vector3(n0.x * radius, 0f, n0.z * radius)
            positions += Vector3(n1.x * radius, 0f, n1.z * radius)
            positions += Vector3(n1.x * radius, height, n1.z * radius)
            positions += Vector3(n0.x * radius, height, n0.z * radius)
            normals += n0
            normals += n1
            normals += n1
            normals += n0
            indices += base
            indices += base + 2
            indices += base + 1
            indices += base
            indices += base + 3
            indices += base + 2
        }
        // caps
        fun cap(y: Float, up: Boolean) {
            val center = positions.size
            positions += Vector3(0f, y, 0f)
            normals += if (up) UP else Vector3(0f, -1f, 0f)
            for (i in 0 until segments) {
                val a = 2 * PI * i / segments
                positions += Vector3((cos(a) * radius).toFloat(), y, (sin(a) * radius).toFloat())
                normals += if (up) UP else Vector3(0f, -1f, 0f)
            }
            for (i in 0 until segments) {
                val a = center + 1 + i
                val b = center + 1 + (i + 1) % segments
                if (up) {
                    indices += center
                    indices += b
                    indices += a
                } else {
                    indices += center
                    indices += a
                    indices += b
                }
            }
        }
        cap(height, true)
        cap(0f, false)
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, height / 2f, 0f), halfExtent = Vector3(radius, height / 2f, radius)),
            name = name,
        )
    }

    /** Cone with its apex at the origin, its axis along +Z, length 1 and base radius 1 (scaled to use). */
    fun cone(name: String, segments: Int = 16): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        positions += Vector3(0f, 0f, 0f)
        normals += Vector3(0f, 0f, -1f)
        for (i in 0 until segments) {
            val a = 2 * PI * i / segments
            positions += Vector3(cos(a).toFloat(), sin(a).toFloat(), 1f)
            normals += Vector3(cos(a).toFloat(), sin(a).toFloat(), 0f)
        }
        for (i in 0 until segments) {
            indices += 0
            indices += 1 + i
            indices += 1 + (i + 1) % segments
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0.5f), halfExtent = Vector3(1f, 1f, 0.5f)),
            name = name,
        )
    }

    /** Unit sphere (radius 1) about the origin: ellipsoids and joint balls are scaled copies. */
    fun unitSphere(name: String, around: Int = 18, rings: Int = 12): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for (j in 0..rings) {
            val phi = PI * j / rings - PI / 2
            val y = sin(phi).toFloat()
            val r = cos(phi).toFloat()
            for (i in 0..around) {
                val a = 2 * PI * i / around
                val v = Vector3(r * cos(a).toFloat(), y, r * sin(a).toFloat())
                positions += v
                normals += v
            }
        }
        val row = around + 1
        for (j in 0 until rings) {
            for (i in 0 until around) {
                val a = j * row + i
                val b = a + row
                indices += a
                indices += b
                indices += a + 1
                indices += a + 1
                indices += b
                indices += b + 1
            }
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(1f, 1f, 1f)),
            name = name,
        )
    }

    /** Closed frustum along +Y from y = 0 (radius 1) to y = 1 (radius [taper]): the limbs, scaled (r, length, r). */
    fun frustum(taper: Float, name: String, segments: Int = 16): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        val slope = 1f - taper
        for (i in 0 until segments) {
            val a0 = 2 * PI * i / segments
            val a1 = 2 * PI * (i + 1) / segments
            val c0 = cos(a0).toFloat()
            val s0 = sin(a0).toFloat()
            val c1 = cos(a1).toFloat()
            val s1 = sin(a1).toFloat()
            val n0 = Vector3(c0, slope, s0)
            val n1 = Vector3(c1, slope, s1)
            val base = positions.size
            positions += Vector3(c0, 0f, s0)
            positions += Vector3(c1, 0f, s1)
            positions += Vector3(c1 * taper, 1f, s1 * taper)
            positions += Vector3(c0 * taper, 1f, s0 * taper)
            normals += n0
            normals += n1
            normals += n1
            normals += n0
            indices += base
            indices += base + 2
            indices += base + 1
            indices += base
            indices += base + 3
            indices += base + 2
        }
        fun cap(y: Float, r: Float, up: Boolean) {
            val center = positions.size
            val n = if (up) Vector3(0f, 1f, 0f) else Vector3(0f, -1f, 0f)
            positions += Vector3(0f, y, 0f)
            normals += n
            for (i in 0 until segments) {
                val a = 2 * PI * i / segments
                positions += Vector3((cos(a) * r).toFloat(), y, (sin(a) * r).toFloat())
                normals += n
            }
            for (i in 0 until segments) {
                val a = center + 1 + i
                val b = center + 1 + (i + 1) % segments
                if (up) {
                    indices += center
                    indices += b
                    indices += a
                } else {
                    indices += center
                    indices += a
                    indices += b
                }
            }
        }
        cap(1f, taper, true)
        cap(0f, 1f, false)
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0.5f, 0f), halfExtent = Vector3(1f, 0.5f, 1f)),
            name = name,
        )
    }

    /** Torus about +Y in the XZ plane: [major] to the tube centre, tube radius [minor]. */
    fun torus(major: Float, minor: Float, name: String, around: Int = 28, tube: Int = 10): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for (i in 0..around) {
            val u = 2 * PI * i / around
            val cu = cos(u).toFloat()
            val su = sin(u).toFloat()
            for (j in 0..tube) {
                val v = 2 * PI * j / tube
                val cv = cos(v).toFloat()
                val sv = sin(v).toFloat()
                positions += Vector3((major + minor * cv) * cu, minor * sv, (major + minor * cv) * su)
                normals += Vector3(cv * cu, sv, cv * su)
            }
        }
        val row = tube + 1
        for (i in 0 until around) {
            for (j in 0 until tube) {
                val a = i * row + j
                val b = a + row
                indices += a
                indices += a + 1
                indices += b
                indices += b
                indices += a + 1
                indices += b + 1
            }
        }
        val r = major + minor
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(r, minor, r)),
            name = name,
        )
    }

    /** Flat triangle in the local XZ plane facing +Y, apex at +Z: the ring's arrow head (size 1). */
    fun arrowHead(name: String): MeshResource {
        val positions = listOf(Vector3(0f, 0f, 1f), Vector3(-0.6f, 0f, 0f), Vector3(0.6f, 0f, 0f))
        val normals = List(3) { UP }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = listOf(0, 2, 1), normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0.5f), halfExtent = Vector3(0.6f, 0.001f, 0.5f)),
            name = name,
        )
    }

    /** One arc piece of a flat ring (XZ plane, facing +Y) from angle 0 to [sweepRad]: timer and progress arcs. */
    fun arcPiece(inner: Float, outer: Float, sweepRad: Float, name: String, steps: Int = 2): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for (i in 0..steps) {
            val a = sweepRad * i / steps
            positions += Vector3(cos(a) * inner, 0f, sin(a) * inner)
            positions += Vector3(cos(a) * outer, 0f, sin(a) * outer)
            normals += UP
            normals += UP
        }
        for (i in 0 until steps) {
            val b = i * 2
            indices += b
            indices += b + 3
            indices += b + 1
            indices += b
            indices += b + 2
            indices += b + 3
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(outer / 2f, 0f, outer / 2f), halfExtent = Vector3(outer, 0.001f, outer)),
            name = name,
        )
    }

    /**
     * Unit cube -0.5..0.5 built from a MeshModel, scaled per entity. (`MeshResource.createBox` renders about twice
     * the asked size on the emulator: factory finding on access-check.)
     */
    fun unitCube(name: String): MeshResource {
        val positions = ArrayList<Vector3>(24)
        val normals = ArrayList<Vector3>(24)
        val indices = ArrayList<Int>(36)
        val faces =
            listOf(
                Triple(Vector3(1f, 0f, 0f), Vector3(0f, 1f, 0f), Vector3(0f, 0f, 1f)),
                Triple(Vector3(-1f, 0f, 0f), Vector3(0f, 1f, 0f), Vector3(0f, 0f, -1f)),
                Triple(Vector3(0f, 1f, 0f), Vector3(0f, 0f, 1f), Vector3(1f, 0f, 0f)),
                Triple(Vector3(0f, -1f, 0f), Vector3(0f, 0f, -1f), Vector3(1f, 0f, 0f)),
                Triple(Vector3(0f, 0f, 1f), Vector3(1f, 0f, 0f), Vector3(0f, 1f, 0f)),
                Triple(Vector3(0f, 0f, -1f), Vector3(-1f, 0f, 0f), Vector3(0f, 1f, 0f)),
            )
        for ((n, u, v) in faces) {
            val base = positions.size
            for ((a, b) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                positions += Vector3((n.x + u.x * a + v.x * b) * 0.5f, (n.y + u.y * a + v.y * b) * 0.5f, (n.z + u.z * a + v.z * b) * 0.5f)
                normals += n
            }
            // u x v points along n for every face above: counter-clockwise seen from outside
            indices += base
            indices += base + 1
            indices += base + 2
            indices += base
            indices += base + 2
            indices += base + 3
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.5f)),
            name = name,
        )
    }
}

/** A straight strip between two Stage points that turns its face to [faceNormal] (needle, leaders). */
internal class Strip(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    private val entity: Entity = ModelEntity(mesh, material)
    private var on = true

    init {
        entity.named(name)
        parent.addChild(entity)
        hide()
    }

    fun draw(from: Vec3, to: Vec3, width: Float, faceNormal: Vec3) {
        val full = to - from
        val length = full.length
        if (length < 1e-4f) {
            hide()
            return
        }
        val dir = full / length
        var z = faceNormal.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.UP.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.RIGHT.rejectFrom(dir)
        z = z.normalized()
        entity.setLocal(position = from, rotation = Quat4.fromBasis(dir, z.cross(dir).normalized(), z), scale = Vec3(length, width, 1f))
        if (!on) {
            entity.enabled = true
            on = true
        }
    }

    fun setMaterial(value: Material) = entity.setMaterial(value)

    fun hide() {
        if (on) {
            entity.enabled = false
            on = false
        }
    }

    fun destroy() {
        runCatching { entity.destroy(true) }
    }
}

/** Time-based easing helpers. */
internal object Ease {
    fun inOutCubic(t: Float): Float = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f

    fun outCubic(t: Float): Float = 1f - (1f - t).let { it * it * it }
}
