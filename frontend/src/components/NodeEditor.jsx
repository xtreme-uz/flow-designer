import { useState, useEffect, useRef } from 'react';
import './NodeEditor.css';

/**
 * Side panel for editing node properties
 */
export default function NodeEditor({ node, availableStatuses = [], onUpdate, onClose }) {
  const [formData, setFormData] = useState({
    statusId: '',
    description: '',
    action: {
      moduleId: '',
      actionId: '',
      maxTryingTime: '',
      warningTryingTime: ''
    }
  });

  // Combobox state
  const [statusQuery, setStatusQuery] = useState('');
  const [showDropdown, setShowDropdown] = useState(false);
  const dropdownRef = useRef(null);
  const inputRef = useRef(null);

  useEffect(() => {
    if (node) {
      setFormData({
        statusId: node.data.statusId || '',
        description: node.data.description || '',
        action: node.data.action || {
          moduleId: '',
          actionId: '',
          maxTryingTime: '',
          warningTryingTime: ''
        }
      });
      setStatusQuery(node.data.statusId || '');
    }
  }, [node]);

  // Close dropdown on outside click
  useEffect(() => {
    const handleClickOutside = (e) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target)) {
        setShowDropdown(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const filteredStatuses = availableStatuses.filter((s) => {
    const q = statusQuery.toLowerCase();
    return (s.id && s.id.toLowerCase().includes(q)) ||
           (s.description && s.description.toLowerCase().includes(q));
  });

  const handleStatusInputChange = (value) => {
    setStatusQuery(value);
    setFormData(prev => ({ ...prev, statusId: value }));
    setShowDropdown(true);
  };

  const handleStatusSelect = (status) => {
    setStatusQuery(status.id);
    setFormData(prev => ({
      ...prev,
      statusId: status.id,
      description: status.description || prev.description
    }));
    setShowDropdown(false);
  };

  const handleChange = (field, value) => {
    setFormData(prev => ({ ...prev, [field]: value }));
  };

  const handleActionChange = (field, value) => {
    setFormData(prev => ({
      ...prev,
      action: { ...prev.action, [field]: value }
    }));
  };

  const handleSubmit = (e) => {
    e.preventDefault();
    onUpdate(node.id, formData);
  };

  if (!node) return null;

  const isInitialOrStatus = node.type === 'initialNode' || node.type === 'statusNode';
  const isFinal = node.type === 'finalNode';

  return (
    <aside className="node-editor">
      <div className="editor-header">
        <h3>Edit Node</h3>
        <button className="close-btn" onClick={onClose}>✕</button>
      </div>

      <form onSubmit={handleSubmit}>
        <div className="form-group">
          <label htmlFor="statusId">Status ID *</label>
          <div className="combobox-wrapper" ref={dropdownRef}>
            <input
              id="statusId"
              ref={inputRef}
              type="text"
              value={statusQuery}
              onChange={(e) => handleStatusInputChange(e.target.value)}
              onFocus={() => setShowDropdown(true)}
              placeholder="e.g., ACCEPTED, PROCESSING"
              required
              autoComplete="off"
            />
            {showDropdown && filteredStatuses.length > 0 && (
              <ul className="combobox-dropdown">
                {filteredStatuses.map((s) => (
                  <li
                    key={s.id}
                    className={`combobox-option ${s.id === formData.statusId ? 'selected' : ''}`}
                    onMouseDown={() => handleStatusSelect(s)}
                  >
                    <span className="combobox-option-id">{s.id}</span>
                    {s.description && (
                      <span className="combobox-option-desc">{s.description}</span>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>
          <small>Select existing status or type a new one</small>
        </div>

        <div className="form-group">
          <label htmlFor="description">Description</label>
          <textarea
            id="description"
            value={formData.description}
            onChange={(e) => handleChange('description', e.target.value)}
            placeholder="Brief description of this status"
            rows={3}
          />
        </div>

        {isInitialOrStatus && (
          <>
            <div className="section-header">Action Configuration</div>

            <div className="form-group">
              <label htmlFor="moduleId">Module ID</label>
              <input
                id="moduleId"
                type="text"
                value={formData.action.moduleId}
                onChange={(e) => handleActionChange('moduleId', e.target.value)}
                placeholder="e.g., source-payment-actor"
              />
            </div>

            <div className="form-group">
              <label htmlFor="actionId">Action ID</label>
              <input
                id="actionId"
                type="text"
                value={formData.action.actionId}
                onChange={(e) => handleActionChange('actionId', e.target.value)}
                placeholder="e.g., realize-debit"
              />
            </div>

            <div className="form-row">
              <div className="form-group">
                <label htmlFor="maxTryingTime">Max Trying Time</label>
                <input
                  id="maxTryingTime"
                  type="text"
                  value={formData.action.maxTryingTime}
                  onChange={(e) => handleActionChange('maxTryingTime', e.target.value)}
                  placeholder="e.g., 30m, 2h"
                />
              </div>

              <div className="form-group">
                <label htmlFor="warningTryingTime">Warning Time</label>
                <input
                  id="warningTryingTime"
                  type="text"
                  value={formData.action.warningTryingTime}
                  onChange={(e) => handleActionChange('warningTryingTime', e.target.value)}
                  placeholder="e.g., 10m"
                />
              </div>
            </div>
          </>
        )}

        {isFinal && (
          <div className="info-box">
            <strong>Final Node</strong>
            <p>Final nodes do not have action configurations.</p>
          </div>
        )}

        <div className="form-actions">
          <button type="submit" className="btn btn-primary">
            Save Changes
          </button>
          <button type="button" className="btn btn-secondary" onClick={onClose}>
            Cancel
          </button>
        </div>
      </form>
    </aside>
  );
}
