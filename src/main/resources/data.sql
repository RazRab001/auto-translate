-- =====================================================================
-- Seed data pro demo: 8 reprezentativních položek průmyslového katalogu
-- (kategorie A: krátké popisy, kategorie B: delší texty s HTML).
-- Stejná struktura jako reálné Maximo ITEM tabulky.
-- =====================================================================

INSERT INTO ITEM (ITEMNUM, DESCRIPTION, LONGDESCRIPTION, CATEGORY, UNIT) VALUES
('15240-H2500',
 'Hydraulic Pump H-2500, 500W, heavy-duty',
 '<p>Heavy-duty hydraulic pump for industrial applications. <b>Max pressure</b> 40 bar, flow rate 500 L/min.</p>',
 'HYDRAULICS', 'EA'),

('M12-1.75-ISO4014',
 'M12 x 1.75 Hex Bolt ISO 4014, stainless steel',
 'Hexagonal bolt M12 x 1.75 mm pitch per ISO 4014, A2-70 stainless steel, 80 mm length.',
 'FASTENERS', 'EA'),

('LN-M10-DIN985',
 'Lock nut M10 DIN 985, zinc plated',
 'Self-locking nut M10 with nylon insert per DIN 985, zinc-plated steel for corrosion resistance.',
 'FASTENERS', 'EA'),

('BB-6204-2RS-SKF',
 'Deep groove ball bearing 6204-2RS SKF',
 '<p>Deep groove ball bearing 6204-2RS, double-sided rubber seals, dynamic load 12.7 kN.</p>',
 'BEARINGS', 'EA'),

('GO-VG220-20L',
 'Gear Oil ISO VG 220, 20L canister',
 'Industrial gear oil ISO VG 220, mineral-based, suitable for enclosed gear drives.',
 'LUBRICANTS', 'L'),

('LG-NLGI2-1KG',
 'Lubricating Grease NLGI 2, 1kg',
 'Multi-purpose lithium grease NLGI grade 2, dropping point >180 C.',
 'LUBRICANTS', 'KG'),

('GBX-1-20-PARKER',
 'Gearbox Ratio 1:20 Parker',
 '<p>Helical gearbox, ratio 1:20, max input torque 50 Nm. Cast iron housing.</p>',
 'POWER_TRANSMISSION', 'EA'),

('MS-SIC-50MM',
 'Mechanical seal SiC 50mm',
 'Single mechanical seal with silicon carbide faces, 50 mm shaft diameter, NBR elastomer.',
 'SEALS', 'EA');
