<script setup>
/**
 * 腾讯地图 GL 封装组件：
 *   - 按需加载地图脚本（AK 由后端站点配置下发）
 *   - markers 变化自动重建点位（按租赁状态着色，可自动全览）
 *   - 点击点位弹出信息窗，"查看完整详情"按钮经事件委托抛出 open-detail
 *   - selectable 选点模式：点击地图抛出 map-click（后台地图选点用）
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { loadTMap } from '../api'
import { escapeHtml, fmtPrice, statusInfo } from '../utils/format'

const props = defineProps({
  /** 腾讯地图 Key */
  ak: { type: String, default: '' },
  /** 点位列表（广告牌 VO，含 longitude/latitude/status 等） */
  markers: { type: Array, default: () => [] },
  /** 选点模式：点击地图发出 map-click（后台表单地图选点） */
  selectable: { type: Boolean, default: false },
  /** 首次渲染点位后是否自动缩放至全览（前台进入地图视图时用；后台选点关闭）。
   *  只在首次渲染生效——之后由父组件在筛选变化时显式调用 fitAll() */
  autoFit: { type: Boolean, default: true },
  /** 初始缩放级别 */
  initialZoom: { type: Number, default: 5 },
  /** 是否高亮当前筛选公路的路段 */
  highlightRoad: { type: Boolean, default: false },
  /** 高亮公路名称（用于地图标签） */
  roadName: { type: String, default: '' },
})

const emit = defineEmits(['open-detail', 'map-click', 'map-error'])

const containerRef = ref(null)
const loadError = ref(false)

// TMap 实例不放入响应式系统（避免 Proxy 代理干扰 SDK 内部逻辑）
let map = null
let markerLayer = null
let infoWindow = null
let infoClickHandler = null
let containerEl = null
let disposed = false
let ready = false
// 地图就绪前收到的定位请求（如从列表切到地图视图时），就绪后补执行
let pendingFocus = null
// 当前已渲染点位的签名，用来判断 markers 是否真的变了
let renderedSignature = null
// 是否已做过首次全览（保证进入地图视图时先看全貌，之后不再自动改变视野）
let hasFitted = false
// 公路高亮图层（发光底线 / 主体线 / 公路名称 / 单点光圈）
let roadGlowLayer = null
let roadCoreLayer = null
let roadLabelLayer = null
let roadHaloLayer = null

onMounted(async () => {
  try {
    await loadTMap(props.ak)
    if (disposed || !containerRef.value) return
    initMap()
  } catch (e) {
    loadError.value = true
    emit('map-error', e)
  }
})

onBeforeUnmount(() => {
  disposed = true
  if (containerEl && infoClickHandler) {
    containerEl.removeEventListener('click', infoClickHandler, true)
  }
  infoClickHandler = null
  containerEl = null
  try {
    infoWindow?.close?.()
  } catch (e) {
    /* SDK 已销毁时忽略 */
  }
  infoWindow = null
  try {
    map?.destroy?.()
  } catch (e) {
    /* 地图未初始化或已销毁时忽略 */
  }
  map = null
  markerLayer = null
  roadGlowLayer = null
  roadCoreLayer = null
  roadLabelLayer = null
  roadHaloLayer = null
  pendingFocus = null
  renderedSignature = null
  hasFitted = false
  ready = false
})

function initMap() {
  containerEl = containerRef.value
  map = new TMap.Map(containerRef.value, {
    center: new TMap.LatLng(34.5, 113.0), // 全国视野
    zoom: props.initialZoom,
    minZoom: 4,
    maxZoom: 19,
  })

  // 信息窗"查看完整详情"按钮：InfoWindow 的 DOM 位于地图容器内，
  // 但 TMap 内部会在冒泡阶段阻断事件外传（实测 click 冒不到容器），
  // 因此这里监听捕获阶段——捕获自外向内先执行，不受内部 stopPropagation 影响。
  infoClickHandler = (e) => {
    const btn = e.target && e.target.closest && e.target.closest('.map-info-btn')
    if (!btn) return
    // 详情是盖在地图上的弹窗，气泡不收起来就会在弹窗底下留一层，先关掉再抛事件
    closeInfoWindow()
    emit('open-detail', Number(btn.dataset.id))
  }
  containerEl.addEventListener('click', infoClickHandler, true)

  if (props.selectable) {
    map.on('click', (ev) => {
      closeInfoWindow()
      emit('map-click', { lng: ev.latLng.getLng(), lat: ev.latLng.getLat() })
    })
  }

  ready = true
  renderMarkers()

  // renderMarkers 会先全览一次，若之前有待处理的定位请求，这里再覆盖为精确定位
  if (pendingFocus) {
    applyFocus(pendingFocus.lng, pendingFocus.lat)
    pendingFocus = null
  }
}

/**
 * 彩色图钉图标（SVG，尺寸与 MarkerStyle 的 width/height 一致）。
 *
 * 说明：MarkerStyle 的 color 属性仅在未提供 src 时生效，而 SDK 内部会自带一张
 * 红色默认图钉图（_defaultStyle.src），导致 color 实际永远不生效、所有图钉都是红色。
 * 因此这里为每种租赁状态生成一张彩色 SVG 作为 src，确保状态配色正确呈现。
 */
function pinIcon(color) {
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="22" height="30" viewBox="0 0 22 30">'
    + `<path d="M11 1C5.5 1 1 5.5 1 11c0 6.9 10 17.4 10 17.4S21 17.9 21 11c0-5.5-4.5-10-10-10z"`
    + ` fill="${color}" stroke="#ffffff" stroke-width="1.6"/>`
    + '<circle cx="11" cy="11" r="3.6" fill="#ffffff"/></svg>'
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg)
}

/** 状态图钉样式（default 兜底为品牌蓝）。 */
function markerStyles() {
  const pin = (color) => new TMap.MarkerStyle({
    width: 22,
    height: 30,
    anchor: { x: 11, y: 30 },
    src: pinIcon(color),
  })
  return {
    AVAILABLE: pin('#16a34a'),
    RENTED: pin('#e11d48'),
    PAUSED: pin('#d97706'),
    default: pin('#2563eb'),
  }
}

/**
 * 点位签名：只有点位增删、坐标或状态变化才算"数据变了"。
 * 价格、名称等字段的改动不重建图层（信息窗每次打开时现取最新数据）。
 */
function markerSignature(list) {
  return (list || [])
    .filter((m) => m.longitude != null && m.latitude != null)
    .map((m) => `${m.id}:${m.longitude}:${m.latitude}:${m.status}`)
    .join('|')
}

/** 沿公路方向对点位排序（PCA 投影到主方向），用于绘制路段高亮线。 */
function orderedRoadPoints(list) {
  const points = (list || [])
    .filter((m) => m.longitude != null && m.latitude != null)
    .map((m) => ({ lng: Number(m.longitude), lat: Number(m.latitude) }))
  if (points.length < 2) return points

  const meanLng = points.reduce((sum, p) => sum + p.lng, 0) / points.length
  const meanLat = points.reduce((sum, p) => sum + p.lat, 0) / points.length
  let covXX = 0
  let covYY = 0
  let covXY = 0
  points.forEach((p) => {
    const dx = p.lng - meanLng
    const dy = p.lat - meanLat
    covXX += dx * dx
    covYY += dy * dy
    covXY += dx * dy
  })
  const angle = 0.5 * Math.atan2(2 * covXY, covXX - covYY)
  const ux = Math.cos(angle)
  const uy = Math.sin(angle)
  points.forEach((p) => {
    p.t = (p.lng - meanLng) * ux + (p.lat - meanLat) * uy
  })
  points.sort((a, b) => a.t - b.t)
  return points
}

/** 清理公路高亮图层。 */
function clearRoadHighlight() {
  [roadGlowLayer, roadCoreLayer, roadLabelLayer, roadHaloLayer].forEach((layer) => {
    if (layer) {
      try {
        layer.setMap(null)
      } catch (e) {
        /* 忽略图层释放异常 */
      }
    }
  })
  roadGlowLayer = null
  roadCoreLayer = null
  roadLabelLayer = null
  roadHaloLayer = null
}

/**
 * 绘制公路高亮：
 *   - 2 个及以上点位：沿公路方向连成发光路线，并标注公路名称；
 *   - 1 个点位：用半透明光圈突出该广告牌。
 */
function renderRoadHighlight() {
  if (!ready || !props.highlightRoad) return
  const points = orderedRoadPoints(props.markers)
  if (!points.length) return

  try {
    if (points.length >= 2) {
      const path = points.map((p) => new TMap.LatLng(p.lat, p.lng))
      roadGlowLayer = new TMap.MultiPolyline({
        map,
        styles: {
          glow: new TMap.PolylineStyle({ color: 'rgba(26, 95, 180, 0.16)', width: 14, lineCap: 'round' }),
        },
        geometries: [{ id: 'road-glow', styleId: 'glow', paths: path }],
      })
      roadCoreLayer = new TMap.MultiPolyline({
        map,
        styles: {
          core: new TMap.PolylineStyle({ color: '#1a5fb4', width: 4, lineCap: 'round' }),
        },
        geometries: [{ id: 'road-core', styleId: 'core', paths: path }],
      })
    } else {
      const halo = '<svg xmlns="http://www.w3.org/2000/svg" width="120" height="120" viewBox="0 0 120 120">'
        + '<circle cx="60" cy="60" r="52" fill="rgba(26,95,180,0.10)" stroke="rgba(26,95,180,0.55)" stroke-width="2"/></svg>'
      roadHaloLayer = new TMap.MultiMarker({
        map,
        styles: {
          halo: new TMap.MarkerStyle({
            width: 120,
            height: 120,
            anchor: { x: 60, y: 60 },
            src: 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(halo),
          }),
        },
        geometries: [{ id: 'road-halo', styleId: 'halo', position: new TMap.LatLng(points[0].lat, points[0].lng) }],
      })
    }
  } catch (e) {
    clearRoadHighlight()
    return
  }

  if (!props.roadName) return
  const mid = points.length >= 2
    ? {
        lng: (points[0].lng + points[points.length - 1].lng) / 2,
        lat: (points[0].lat + points[points.length - 1].lat) / 2,
      }
    : points[0]
  try {
    roadLabelLayer = new TMap.MultiLabel({
      map,
      styles: {
        road: new TMap.LabelStyle({
          color: '#1a5fb4',
          size: 13,
          offset: { x: 0, y: -8 },
          backgroundColor: '#ffffff',
          borderColor: '#b8d1ea',
          borderWidth: 1,
          padding: '4px 9px',
        }),
      },
      geometries: [{
        id: 'road-label',
        styleId: 'road',
        position: new TMap.LatLng(mid.lat, mid.lng),
        content: props.roadName,
      }],
    })
  } catch (e) {
    roadLabelLayer = null
  }
}

/**
 * markers 变化 → 重建点位图层。
 *
 * 数据没变时直接返回：前台每 15 秒轮询一次，每次都会给 markers 换一个新数组，
 * 若无脑重建，用户手动缩放/平移过的视野和打开的信息窗都会被冲掉。
 */
function renderMarkers() {
  if (!ready) return
  const signature = markerSignature(props.markers)
    + '|' + props.highlightRoad
    + '|' + props.roadName
  if (signature === renderedSignature) return
  renderedSignature = signature

  if (markerLayer) {
    markerLayer.setMap(null)
    markerLayer = null
  }
  clearRoadHighlight()
  closeInfoWindow()
  const valid = (props.markers || []).filter(
    (m) => m.longitude != null && m.latitude != null,
  )
  if (!valid.length) return

  // 公路高亮线在点位下方
  renderRoadHighlight()

  const geometries = valid.map((m) => ({
    id: 'b' + m.id,
    styleId: statusInfo(m.status).cls ? m.status : 'default',
    position: new TMap.LatLng(Number(m.latitude), Number(m.longitude)),
  }))
  markerLayer = new TMap.MultiMarker({
    map,
    styles: markerStyles(),
    geometries,
  })
  markerLayer.on('click', (ev) => {
    if (ev.geometry && ev.geometry.id) {
      openInfoWindow(Number(ev.geometry.id.slice(1)))
    }
  })

  // 全览只在首次渲染做一次，之后由父组件在筛选变化时显式调用 fitAll()，
  // 否则后台数据一有风吹草动就会把用户当前的视野拉回去
  if (props.autoFit && !hasFitted) {
    hasFitted = true
    fitAll()
  }
}

watch(
  [() => props.markers, () => props.highlightRoad, () => props.roadName],
  renderMarkers,
)

/** 全览：缩放至覆盖当前全部点位。 */
function fitAll() {
  const valid = (props.markers || []).filter(
    (m) => m.longitude != null && m.latitude != null,
  )
  if (!valid.length || !map) return
  let minLat = 90, maxLat = -90, minLng = 180, maxLng = -180
  valid.forEach((m) => {
    const lat = Number(m.latitude), lng = Number(m.longitude)
    if (lat < minLat) minLat = lat
    if (lat > maxLat) maxLat = lat
    if (lng < minLng) minLng = lng
    if (lng > maxLng) maxLng = lng
  })
  // 留出约 12% 的边缘，避免最外侧点位的图钉贴边被裁切
  const latPad = Math.max((maxLat - minLat) * 0.12, 0.03)
  const lngPad = Math.max((maxLng - minLng) * 0.12, 0.03)
  try {
    map.fitBounds(new TMap.LatLngBounds(
      new TMap.LatLng(minLat - latPad, minLng - lngPad),
      new TMap.LatLng(maxLat + latPad, maxLng + lngPad)))
  } catch (e) {
    map.setCenter(new TMap.LatLng((minLat + maxLat) / 2, (minLng + maxLng) / 2))
  }
}

/** 收起信息窗并释放引用（未打开时无副作用）。 */
function closeInfoWindow() {
  if (!infoWindow) return
  infoWindow.close()
  infoWindow = null
}

/** 点击点位：居中 + 信息窗（含"查看完整详情"入口）。 */
function openInfoWindow(id) {
  const item = (props.markers || []).find((m) => m.id === id)
  if (!item || item.longitude == null) return
  const pos = new TMap.LatLng(Number(item.latitude), Number(item.longitude))
  try {
    map.easeTo({ center: pos, zoom: Math.max(map.getZoom(), 10) })
  } catch (e) {
    map.setCenter(pos)
  }
  closeInfoWindow()
  const st = statusInfo(item.status)
  infoWindow = new TMap.InfoWindow({
    map,
    position: pos,
    offset: { x: 0, y: -34 },
    content: `
      <div class="map-info">
        <div class="map-info-title">${escapeHtml(item.name)}</div>
        <div class="map-info-price">¥${fmtPrice(item.price)} <span>${escapeHtml(item.priceUnit || '')}</span></div>
        <div class="map-info-road">${escapeHtml(item.road || '')} ${escapeHtml(item.section || '')}</div>
        <div style="margin-bottom:6px;"><span class="badge ${st.cls}">${st.label}</span></div>
        <button class="map-info-btn" data-id="${item.id}">查看完整详情</button>
      </div>`,
  })
  infoWindow.open()
}

/**
 * 定位到指定坐标（父组件通过 ref 调用）。
 * 地图脚本尚未加载完时先暂存，等 initMap 结束后自动执行，避免定位请求被吞掉。
 */
function focusOn(lng, lat) {
  if (lng == null || lat == null) return
  if (!ready) {
    pendingFocus = { lng, lat }
    return
  }
  applyFocus(lng, lat)
}

/** 真正执行居中（调用方需保证地图已就绪）。 */
function applyFocus(lng, lat) {
  const pos = new TMap.LatLng(Number(lat), Number(lng))
  try {
    map.easeTo({ center: pos, zoom: 10 })
  } catch (e) {
    map.setCenter(pos)
  }
}

defineExpose({ focusOn, fitAll })
</script>

<template>
  <div class="tmap-root">
    <div ref="containerRef" class="tmap-container"></div>
    <div v-if="loadError" class="map-error">地图加载失败，请检查网络后刷新页面</div>
  </div>
</template>

<style scoped>
.tmap-root {
  position: relative;
  width: 100%;
  height: 100%;
}

.tmap-container {
  width: 100%;
  height: 100%;
}
</style>
