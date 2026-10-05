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
 * 只有"待支付"能取消。
 *
 * 这与后端一致（后端对 PAID 也拒绝：退号涉及退费，而本项目明确不做真实退费）。
 * 前端把按钮藏起来是为了**不让用户点了才知道**——但真正拦住它的是后端。
 */
function canCancel(row) {
  return row.status === 'PENDING_PAYMENT'
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
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button
              v-if="canCancel(row)"
              size="small"
              :loading="cancellingNo === row.appointmentNo"
              @click="cancel(row)"
            >
              取消
            </el-button>
            <span v-else class="muted">—</span>
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
