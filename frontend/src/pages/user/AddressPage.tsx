import { useState, useEffect } from 'react';
import { Button, Spin, Empty, Modal, Form, Input, Switch, Tag, message} from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { getAddressList, addAddress, updateAddress, deleteAddress, setDefaultAddress } from '../../api/auth';
import type { AddressVO, AddressRequest } from '../../types';

export default function AddressPage() {
  const [addresses, setAddresses] = useState<AddressVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<AddressVO | null>(null);
  const [form] = Form.useForm<AddressRequest>();
  const [submitting, setSubmitting] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const resp = await getAddressList();
      setAddresses(resp.data.data || []);
    } catch (e: any) {
      message.error(e.response?.data?.message || '加载地址失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setModalOpen(true);
  };

  const openEdit = (addr: AddressVO) => {
    setEditing(addr);
    form.setFieldsValue({
      receiverName: addr.receiverName,
      receiverPhone: addr.receiverPhone,
      province: addr.province,
      city: addr.city,
      district: addr.district,
      detailAddress: addr.detailAddress,
    });
    setModalOpen(true);
  };

  const handleSubmit = async (values: AddressRequest) => {
    setSubmitting(true);
    try {
      if (editing) {
        await updateAddress(editing.id, values);
        message.success('地址已更新');
      } else {
        await addAddress(values);
        message.success('地址已添加');
      }
      setModalOpen(false);
      load();
    } catch (e: any) {
      message.error(e.response?.data?.message || '保存失败');
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = (id: string | number) => {
    Modal.confirm({
      title: '确认删除该地址？',
      onOk: async () => {
        try {
          await deleteAddress(id);
          message.success('已删除');
          load();
        } catch (e: any) { message.error(e.response?.data?.message || '删除失败'); }
      },
    });
  };

  const handleSetDefault = (id: string | number) => {
    setDefaultAddress(id)
      .then(() => { message.success('已设为默认'); load(); })
      .catch((e: any) => message.error(e.response?.data?.message || '操作失败'));
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>;

  return (
    <div style={{ maxWidth: 700, margin: '0 auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>收货地址</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增地址</Button>
      </div>

      {addresses.length === 0 ? (
        <Empty description="暂无收货地址" style={{ padding: 60 }} />
      ) : (
        <div>
          {addresses.map(addr => (
            <div key={addr.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px 0', borderBottom: '1px solid #f0f0f0' }}>
              <div>
                <div><span>{addr.receiverName} {addr.receiverPhone}</span>{addr.isDefault === 1 && <Tag color="red">默认</Tag>}</div>
                <div style={{ color: '#999' }}>{addr.province}{addr.city}{addr.district}{addr.detailAddress}</div>
              </div>
              <div style={{ display: 'flex', gap: 8 }}>
                {addr.isDefault !== 1 && (
                  <Button size="small" onClick={() => handleSetDefault(addr.id)}>设为默认</Button>
                )}
                <Button size="small" onClick={() => openEdit(addr)}>编辑</Button>
                <Button size="small" danger onClick={() => handleDelete(addr.id)}>删除</Button>
              </div>
            </div>
          ))}
        </div>
      )}

      <Modal
        title={editing ? '编辑地址' : '新增地址'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" onFinish={handleSubmit}>
          <Form.Item name="receiverName" label="收货人" rules={[{ required: true, message: '请输入收货人' }]}>
            <Input placeholder="收货人姓名" />
          </Form.Item>
          <Form.Item name="receiverPhone" label="手机号" rules={[
            { required: true, message: '请输入手机号' },
            { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确' },
          ]}>
            <Input placeholder="手机号" />
          </Form.Item>
          <Form.Item name="province" label="省份" rules={[{ required: true, message: '请输入省份' }]}>
            <Input placeholder="省份" />
          </Form.Item>
          <Form.Item name="city" label="城市" rules={[{ required: true, message: '请输入城市' }]}>
            <Input placeholder="城市" />
          </Form.Item>
          <Form.Item name="district" label="区县" rules={[{ required: true, message: '请输入区县' }]}>
            <Input placeholder="区县" />
          </Form.Item>
          <Form.Item name="detailAddress" label="详细地址" rules={[{ required: true, message: '请输入详细地址' }]}>
            <Input placeholder="街道、门牌号等" />
          </Form.Item>
          <Form.Item name="isDefault" label="设为默认" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
