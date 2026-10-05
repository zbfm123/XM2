<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

/**
 * 我的挂号（T-016 取消按钮 / T-009 列表）。
 *
 * 对应接口：
 *   GET  /api/appointments?status=&page=&size=
 *   POST /api/appointments/{no}/cancel
 *
 * ⚠️ 这个页面**没有**"用户 id"这个参数——后端从 JWT 取当前用户，
 *    查询本身就只可能查到自己的。前端也就不可能传错别人的 id。
 */

const router = useRouter()

const items = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const totalPages = ref(0)
const status = ref('')
const loading = ref(true)
const error = ref('')
const cancellingNo = ref(null)
/** 正在推进状态的单号（两个动作共用，避免按钮重复点）。 */
const actingNo = ref(null)

/** 状态筛选项。值必须与后端 AppointmentStatus 枚举**逐字一致**。 */
const statusOptions = [
  { label: '全部', value: '' },
  { label: '待支付', value: 'PENDING_PAYMENT' },
  { label: '已支付', value: 'PAID' },
  { label: '已完成', value: 'COMPLETED' },
  { label: '已取消', value: 'CANCELLED' }
]

const statusText = {
  PENDING_PAYMENT: '待支付',
  PAID: '已支付',
  COMPLETED: '已完成',
  CANCELLED: '已取消'
}

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const params = { page: page.value, size: size.value }
    // 空字符串不能传给后端：它是"全部"，而不是一个状态值。
    // 传了会让后端把 status="" 当成枚举解析失败 → 400。
    if (status.value) {
      params.status = status.value
    }
    const res = await api.myAppointments(params)
    items.value = res.items
    total.value = res.total
    totalPages.value = res.totalPages
  } catch (e) {
    error.value = e.friendlyMessage || '加载我的挂号失败'
  } finally {
    loading.value = false
  }
}

function changeStatus() {
  page.value = 1
  load()
}

function changePage(p) {
  page.value = p
  load()
}

function periodLabel(period) {
  if (period === 'AM') return '上午'
  if (period === 'PM') return '下午'
  return period
}

async function cancel(row) {
  try {
    await ElMessageBox.confirm(
      `确认取消 ${row.visitDate} ${periodLabel(row.period)} ${row.doctorName} 的挂号吗？`,
      '取消挂号',
      { confirmButtonText: '确认取消', cancelButtonText: '再想想', type: 'warning' }
    )
  } catch {
    // 用户点了"再想想"：什么都不做。
    // ⚠️ ElMessageBox 在取消时抛的是 reject，必须接住，否则会变成未处理异常。
    return
  }

  cancellingNo.value = row.appointmentNo
  try {
    await api.cancel(row.appointmentNo, '用户主动取消')
    ElMessage.success('已取消，号源已归还')
    await load()
  } catch (e) {
    ElMessage.error(e.friendlyMessage || '取消失败')
    await load()
  } finally {
    cancellingNo.value = null
  }
}

/**
 * 按钮显隐规则 —— **与后端的状态机严格对应**。
 *
 * ⚠️ 前端把按钮藏起来只是为了**不让用户点了才知道**；
 * 真正拦住非法操作的是后端（状态机 + 带起始状态条件的原子 UPDATE）。
 * 所以这里的判断标准只有一条：**后端会不会允许**。
 *
 * 对应关系（见 AppointmentStatus）：
 *   PENDING_PAYMENT → 可支付、可取消
 *   PAID            → 可标记完成；**不可取消**（退号涉及退费，本项目不做真实退费）
 *   COMPLETED / CANCELLED → 终态，无任何操作
 */
function canCancel(row) {
  return row.status === 'PENDING_PAYMENT'
}
function canPay(row) {
  return row.status === 'PENDING_PAYMENT'
}
function canComplete(row) {
  return row.status === 'PAID'
}

/** 调用后端推进状态。action 决定调哪个接口。 */
async function advance(row, action) {
  const label = action === 'pay' ? '支付' : '就诊完成'
  try {
    await ElMessageBox.confirm(
      `确认将 ${row.appointmentNo} 标记为「${label}」吗？`,
      label,
      { confirmButtonText: `确认${label}`, cancelButtonText: '取消', type: 'info' }
    )
  } catch {
    return
  }

  actingNo.value = row.appointmentNo
  try {
    if (action === 'pay') {
      await api.pay(row.appointmentNo)
    } else {
      await api.complete(row.appointmentNo)
    }
    ElMessage.success(`已标记为${label}`)
    await load()
  } catch (e) {
    ElMessage.error(e.friendlyMessage || `${label}失败`)
    await load()
  } finally {
    actingNo.value = null
  }
}
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <h2 class="page-title" style="margin: 0">我的挂号</h2>
      <el-select
        v-model="status"
        placeholder="按状态筛选"
        style="width: 140px; margin-left: auto"
        @change="changeStatus"
      >
        <el-option
          v-for="o in statusOptions"
          :key="o.value"
          :label="o.label"
          :value="o.value"
        />
      </el-select>
      <el-button size="small" @click="router.push({ name: 'departments' })">
        去挂号
      </el-button>
    </div>

    <div v-if="error" class="hint hint-err">{{ error }}</div>

    <div v-loading="loading">
      <el-empty
        v-if="!loading && !error && items.length === 0"
        description="还没有挂号记录"
      />

      <el-table v-if="items.length" :data="items" border style="width: 100%">
        <el-table-column prop="appointmentNo" label="单号" width="200" />
        <el-table-column prop="doctorName" label="医生" width="100" />
        <el-table-column prop="departmentName" label="科室" width="100" />
        <el-table-column label="就诊时间" width="160">
          <template #default="{ row }">
            {{ row.visitDate }} {{ periodLabel(row.period) }}
          </template>
        </el-table-column>
        <el-table-column label="挂号费" width="90">
          <template #default="{ row }">¥{{ row.fee }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 'CANCELLED' ? 'info'
                   : row.status === 'PENDING_PAYMENT' ? 'warning' : 'success'"
            >
              {{ statusText[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210">
          <template #default="{ row }">
            <!--
              按钮按状态显隐，与后端状态机一一对应。
              「模拟支付」是刻意保留的演示入口（决策 D-07）：真实系统里这一步
              由支付平台回调触发。没有它，PAID 与 COMPLETED 在界面上不可达，
              而"已支付的订单不会被超时误取消"这条也就演示不出来。
            -->
            <el-button
              v-if="canPay(row)"
              type="primary"
              size="small"
              :loading="actingNo === row.appointmentNo"
              @click="advance(row, 'pay')"
            >
              模拟支付
            </el-button>
            <el-button
              v-if="canComplete(row)"
              type="success"
              size="small"
              :loading="actingNo === row.appointmentNo"
              @click="advance(row, 'complete')"
            >
              就诊完成
            </el-button>
            <el-button
              v-if="canCancel(row)"
              size="small"
              :loading="cancellingNo === row.appointmentNo"
              @click="cancel(row)"
            >
              取消
            </el-button>
            <span v-if="!canPay(row) && !canComplete(row) && !canCancel(row)" class="muted">
              {{ row.status === 'CANCELLED' ? '已取消' : '已完成' }}
            </span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="totalPages > 1" class="pager">
        <el-pagination
          layout="prev, pager, next"
          :current-page="page"
          :page-size="size"
          :total="total"
          @current-change="changePage"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.pager {
    margin-top: 1rem;
    display: flex;
    justify-content: center;
}
</style>
